#!/bin/bash -e

cd "$( dirname "${BASH_SOURCE[0]}" )"
. ./include/depinfo.sh

cleanbuild=0
nodeps=0
onlydeps=0
target=mpv-android
arch=armv7l

getdeps () {
	varname="dep_${1//-/_}[*]"
	echo ${!varname}
}

wasbuilt () {
	# don't use associative arrays to support bash 3 on macOS (thanks Tim Apple)
	varname="built_${1//-/_}"
	return "${!varname:-1}"
}

markbuilt () {
	varname="built_${1//-/_}"
	declare -g "$varname=0"
}

loadndk () {
	local ndk="$PWD/sdk/android-ndk-${v_ndk}"
	local toolchain=$(echo "$ndk/toolchains/llvm/prebuilt/"*)
	if [ ! -d "$toolchain" ]; then
		echo "Can't find toolchain inside NDK" >&2
		return 1
	fi
	export ANDROID_NDK_ROOT="$ndk"
	export PATH="$toolchain/bin:$ndk:$PWD/sdk/bin:$PATH"
}

loadarch () {
	unset CC CXX CPATH LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH
	unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS
	unset PKG_CONFIG_PATH

	local apilvl=23
	# ndk_triple: the target triple
	local cc_triple # how the compilers are actually prefixed
	if [[ "$1" == "armv7l" ]]; then
		export ndk_triple=arm-linux-androideabi
		cc_triple=armv7a-linux-androideabi$apilvl
		prefix_name=armv7l
	elif [[ "$1" == "arm64" ]]; then
		export ndk_triple=aarch64-linux-android
		cc_triple=$ndk_triple$apilvl
		prefix_name=arm64
	elif [[ "$1" == "x86" ]]; then
		export ndk_triple=i686-linux-android
		cc_triple=$ndk_triple$apilvl
		prefix_name=x86
	elif [[ "$1" == "x86_64" ]]; then
		export ndk_triple=x86_64-linux-android
		cc_triple=$ndk_triple$apilvl
		prefix_name=x86_64
	else
		echo "Invalid architecture" >&2
		exit 1
	fi
	export ndk_suffix=_$prefix_name
	export prefix_dir="$PWD/prefix/$prefix_name"
	# 用 ccache 包一层: CI 里缓存了 ccache 目录, 首次编译没有缓存可用没法加速,
	# 但只要这次编译失败过一次要重跑, 或者以后改了跟 mpv 无关的代码要重新触发,
	# 已经编译过的目标文件可以直接复用, 不用整个重编一遍。
	#
	# 注意:
	#   CC/CXX 带 "ccache " 前缀是给 Makefile 类构建(autoconf/configure)用的,
	#   shell 会把 "ccache aarch64-linux-android23-clang" 正确拆成两个 argv。
	#   但 meson 的 crossfile [binaries] 不做 shell 分词, 整串会被当成一个
	#   可执行文件名去 execve, 直接 ENOENT:
	#     ERROR: Unknown compiler(s): [['ccache aarch64-linux-android23-clang']]
	#   所以 crossfile 里必须改用数组形式 ['ccache', '<clang>']。
	#   下面用 CCACHE_WRAP 记录是否真的包了 ccache, 供 setup_prefix 生成
	#   crossfile 时选择正确的写法。
	if command -v ccache >/dev/null 2>&1; then
		export CC="ccache $cc_triple-clang"
		export CXX="ccache $cc_triple-clang++"
		export CCACHE_WRAP=1
	else
		export CC=$cc_triple-clang
		export CXX=$cc_triple-clang++
		export CCACHE_WRAP=0
	fi
	# 供 setup_prefix 生成 crossfile 用的裸编译器名(不带 ccache 前缀)
	export CC_BIN="$cc_triple-clang"
	export CXX_BIN="$cc_triple-clang++"
	export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384"
	export AR=llvm-ar
	export RANLIB=llvm-ranlib

	# set up correct paths for pkg-config
	if ! command -v pkg-config >/dev/null; then
		echo "pkg-config is missing!" >&2
		return 1
	fi
	export PKG_CONFIG_SYSROOT_DIR="$prefix_dir"
	export PKG_CONFIG_LIBDIR="$PKG_CONFIG_SYSROOT_DIR/lib/pkgconfig"
}

setup_prefix () {
	if [ ! -d "$prefix_dir" ]; then
		mkdir -p "$prefix_dir"
		# enforce flat structure (/usr/local -> /)
		ln -s . "$prefix_dir/usr"
		ln -s . "$prefix_dir/local"
	fi

	local cpu_family=${ndk_triple%%-*}
	[[ "$cpu_family" == "i686" ]] && cpu_family=x86

	# meson 的 [binaries] 不做 shell 分词, 所以:
	#   有 ccache -> c = ['ccache', 'aarch64-linux-android23-clang']
	#   无 ccache -> c = 'aarch64-linux-android23-clang'
	# 不能把 "ccache <clang>" 拼成一个字符串写进去, 否则 meson 会把它当成
	# 单个可执行文件名, execve 报 ENOENT。
	if [ "$CCACHE_WRAP" = "1" ]; then
		c_entry="['ccache', '$CC_BIN']"
		cpp_entry="['ccache', '$CXX_BIN']"
	else
		c_entry="'$CC_BIN'"
		cpp_entry="'$CXX_BIN'"
	fi

	# meson wants to be spoonfed this file, so create it ahead of time
	# also define: release build, static libs and no source downloads at runtime(!!!)
	cat >"$prefix_dir/crossfile.tmp" <<CROSSFILE
[built-in options]
buildtype = 'release'
default_library = 'static'
wrap_mode = 'nodownload'
prefix = '/usr/local'
[binaries]
c = $c_entry
cpp = $cpp_entry
ar = 'llvm-ar'
nm = 'llvm-nm'
strip = 'llvm-strip'
pkgconfig = 'pkg-config'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$cpu_family'
endian = 'little'
CROSSFILE
	# also avoid rewriting it needlessly
	if cmp -s "$prefix_dir"/crossfile.{tmp,txt}; then
		rm "$prefix_dir/crossfile.tmp"
	else
		mv "$prefix_dir"/crossfile.{tmp,txt}
	fi
}

build () {
	if [[ $1 != "mpv-android" && ! -d deps/$1 ]]; then
		printf >&2 '\e[1;31m%s\e[m\n' "Target $1 not found"
		return 1
	fi
	wasbuilt "$1" && return 0
	if [ $nodeps -eq 0 ]; then
		printf >&2 '\e[1;34m%s\e[m\n' "Preparing $1..."
		local deps=$(getdeps $1)
		echo >&2 "Dependencies: $deps"
		for dep in $deps; do
			build $dep
		done
	fi
	printf >&2 '\e[1;34m%s\e[m\n' "Building $1..."
	if [[ "$1" == "mpv-android" ]]; then
		pushd ..
		BUILDSCRIPT=buildscripts/scripts/$1.sh
	else
		pushd deps/$1
		BUILDSCRIPT=../../scripts/$1.sh
	fi
	[ $cleanbuild -eq 1 ] && $BUILDSCRIPT clean
	$BUILDSCRIPT build
	popd
	markbuilt $1
}

usage () {
	printf '%s\n' \
		"Usage: buildall.sh [options] [target]" \
		"Builds the specified target (default: $target)" \
		"" \
		"-n             Do not build dependencies" \
		"--only-deps    Build only dependencies of the specified target" \
		"--clean        Clean build dirs before compiling" \
		"--arch <arch>  Build for specified architecture (default: $arch; supported: armv7l, arm64, x86, x86_64)"
	exit 0
}

while [ $# -gt 0 ]; do
	case "$1" in
		--clean)
		cleanbuild=1
		;;
		-n|--no-deps)
		nodeps=1
		;;
		--only-deps)
		onlydeps=1
		;;
		--arch)
		shift
		arch=$1
		;;
		-h|--help)
		usage
		;;
		-*)
		echo "Unknown flag $1" >&2
		exit 1
		;;
		*)
		target=$1
		;;
	esac
	shift
done

loadndk
loadarch $arch
setup_prefix
if [ $onlydeps -eq 1 ]; then
	deps=$(getdeps $target)
	for dep in $deps; do
		build $dep
	done
else
	build $target
fi

# be helpful and list the output APKs (if they exist)
if wasbuilt "mpv-android"; then
	ls -lh ../app/build/outputs/apk/{default,allstorage}/*/*.apk || :
fi

exit 0

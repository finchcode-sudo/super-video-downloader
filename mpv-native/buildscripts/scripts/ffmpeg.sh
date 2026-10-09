#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf _build$ndk_suffix
	exit 0
else
	exit 255
fi

mkdir -p _build$ndk_suffix
cd _build$ndk_suffix

cpu=armv7-a
[[ "$ndk_triple" == "aarch64"* ]] && cpu=armv8-a
[[ "$ndk_triple" == "x86_64"* ]] && cpu=generic
[[ "$ndk_triple" == "i686"* ]] && cpu="i686 --disable-asm"

cpuflags=
[[ "$ndk_triple" == "arm"* ]] && cpuflags="$cpuflags -mfpu=neon -mcpu=cortex-a8"

# ffmpeg 的 configure 处理不了 --cc 值里的空格: 上面 buildall.sh 里
# CC="ccache $cc_triple-clang" 带 "ccache " 前缀, 直接塞给 --cc 会触发
#   Unknown option "aarch64-linux-android23-clang"
# 因为 configure 会把带空格的 --cc 值按 "命令 + 首个裸参数" 解析, 多出来的
# 裸词被当成未知选项。
#
# 判断 ccache 是否可用的正规做法是 masquerade(符号链接), 但为了不改动
# 其它构建系统(mbedtls 的 Makefile 靠 CC 前缀、dav1d 的 meson 靠 crossfile
# 数组), 这里单独给 ffmpeg 剥离 ccache 前缀, 用裸编译器名传给 configure。
# 代价: ffmpeg 这一步不吃 ccache 缓存(只影响这一个依赖的增量编译速度)。
ff_cc=${CC#ccache }
ff_cxx=${CXX#ccache }

args=(
	--target-os=android --enable-cross-compile
	--cross-prefix=$ndk_triple- --cc=$ff_cc --cxx=$ff_cxx --pkg-config=pkg-config --nm=llvm-nm
	--arch=${ndk_triple%%-*} --cpu=$cpu
	--extra-cflags="-I$prefix_dir/include $cpuflags" --extra-ldflags="-L$prefix_dir/lib"

	--enable-{jni,mediacodec,mbedtls,libdav1d,libxml2} --disable-vulkan
	--disable-static --enable-shared --enable-{gpl,version3}

	# disable unneeded parts
	--disable-{stripping,doc,programs}
	# to keep the build lean we disable some feature quite aggressively:
	# - muxers, encoders: mpv-android does not have any way to use these
	# - devices: no practical use on Android
	--disable-{muxers,encoders,devices}
	# useful to taking screenshots
	--enable-encoder=mjpeg,png
	# useful for the `dump-cache` command
	--enable-muxer=mov,matroska,mpegts
)
../configure "${args[@]}"

make -j$cores
make DESTDIR="$prefix_dir" install

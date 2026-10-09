package com.myAllVideoBrowser.ui.main.localplayer

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.ActivityLocalMpvPlayerBinding
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * mpv 版本地视频播放器, 只用于播放已经下载到手机的视频("视频"标签页), 在线流播放
 * (浏览器里点开的视频)继续用原来的 ExoPlayer(VideoPlayerActivity), 没有动。
 *
 * 和 DroidFS 版播放器的区别: 这里的文件是已经下载好的明文文件, 不需要经过加密卷和
 * 本地 HTTP 服务器(LocalMediaServer), 直接把真实文件路径交给 mpv 的 loadfile。
 *
 * 注意: 这是照抄 DroidFS 播放器搬过来的, 没有实际编译/真机跑过, 需要跟着 CI 报错再修。
 */
class LocalMpvPlayerActivity : AppCompatActivity() {

    companion object {
        /** Uri 字符串数组: "视频"标签页里的完整下载列表, 用来支持上一个/下一个 */
        const val VIDEO_URIS = "video_uris"
        /** 当前要播放的是上面数组里的第几个 */
        const val VIDEO_INDEX = "video_index"
    }

    private lateinit var binding: ActivityLocalMpvPlayerBinding
    private lateinit var subtitles: LocalSubtitleController
    private val sharedPrefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private var videoUris: List<Uri> = emptyList()
    private var currentIndex = 0
    /** 当前真实文件路径(content:// 已经解析成能直接打开的真实路径) */
    private var currentRealPath: String? = null

    private var isPlaying = true
    private var isUserSeeking = false
    private var firstPlay = true
    private val autoFit by lazy { sharedPrefs.getBoolean("autoFit", false) }

    // 由 WindowInsets 监听器实时算出来的系统栏高度(px), 用于给顶栏/底栏补 padding。
    // 播放器是 edge-to-edge, 系统不再给 content 预留空间, 控件必须自己避开状态栏/导航栏,
    // 否则顶部的字幕/旋转按钮会被状态栏盖住, 底部的进度条会被导航栏盖住。
    private var statusBarInset = 0
    private var navigationBarInset = 0

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            progressHandler.postDelayed(this, 500)
        }
    }

    // 划屏快进/快退 (和 mpv-android 官方一致)
    private var swipeSeekStartX = 0f
    private var swipeSeekStartY = 0f
    private var swipeSeekStartPositionSec = 0.0
    private var isSwipeSeeking = false
    private val swipeSeekFullWidthSec = 150.0
    private val swipeSeekThresholdPx by lazy {
        minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 30f
    }

    // 长按加速
    private var isLongPressSpeeding = false
    private val longPressSpeedMultiplier = 2.0
    private val longPressTimeoutMs = 350L
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            viewFileInner()
        } catch (e: Throwable) {
            showCrashDialog(e)
        }
    }

    private fun showCrashDialog(e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = sw.toString()
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            textSize = 12f
        }
        scrollView.addView(textView)
        android.app.AlertDialog.Builder(this)
            .setTitle("视频播放器出错")
            .setView(scrollView)
            .setPositiveButton("关闭") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * 播放器是 edge-to-edge(见 hideSystemUi/onResume), 系统不会给 content 预留空间,
     * 系统栏是叠在 content 上面的。所以这里监听 WindowInsets, 把状态栏/导航栏高度
     * 作为额外 padding 加到顶栏/底栏上, 保证字幕/旋转按钮不被状态栏盖、进度条不被
     * 导航栏盖。横竖屏切换、手势/三键导航切换时会自动重算。
     */
    private fun applyWindowInsets() {
        val density = resources.displayMetrics.density
        // 顶栏/底栏自身本来就有的基础内边距(和布局里的值保持一致)
        val topBarBaseTop = (8 * density).toInt()
        val bottomBarBaseBottom = (12 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarInset = bars.top
            navigationBarInset = bars.bottom

            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                topBarBaseTop + statusBarInset,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                bottomBarBaseBottom + navigationBarInset
            )
            insets
        }
        // 主动请求一次, 保证首帧就拿到正确的 insets
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun viewFileInner() {
        binding = ActivityLocalMpvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        videoUris = intent.getStringArrayExtra(VIDEO_URIS)?.map { it.toUri() } ?: run {
            // 没传列表就只播单个(理论上不会发生, 兜个底)
            intent.data?.let { listOf(it) } ?: emptyList()
        }
        currentIndex = intent.getIntExtra(VIDEO_INDEX, 0).coerceIn(0, (videoUris.size - 1).coerceAtLeast(0))

        if (videoUris.isEmpty()) {
            showCrashDialog(IllegalStateException("No video to play"))
            return
        }

        subtitles = LocalSubtitleController(this, { currentRealPath ?: "" }, sharedPrefs)
        binding.subtitleButton.setOnClickListener { subtitles.showMenu() }
        binding.rotateButton.setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
        }

        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.prevButton.setOnClickListener { playlistStep(-1) }
        binding.nextButton.setOnClickListener { playlistStep(1) }
        binding.rewindButton.setOnClickListener {
            MPVLib.command(arrayOf("no-osd", "seek", "-10", "relative"))
        }
        binding.forwardButton.setOnClickListener {
            MPVLib.command(arrayOf("no-osd", "seek", "10", "relative"))
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                if (duration > 0) {
                    val target = duration * (seekBar!!.progress / 1000.0)
                    MPVLib.command(arrayOf("no-osd", "seek", target.toString(), "absolute"))
                }
                isUserSeeking = false
            }
        })

        setupGestures()

        binding.videoPlayer.initialize(filesDir.path, cacheDir.path)
        subtitles.applySaved()
        loadCurrentFile()
        progressHandler.post(progressRunnable)
    }

    /** content:// 解析成真实文件路径, 和 mpv-android 官方 MPVActivity.resolveUri 做法一样;
     *  解析不出真实路径就直接把 content:// 交给 mpv(底层 ffmpeg 也能读 content 协议)。 */
    private fun resolveUri(uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path
        "content" -> translateContentUri(uri)
        else -> uri.toString()
    }

    private fun translateContentUri(uri: Uri): String {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val path = findRealPath(pfd.fd)
                if (path != null) return path
            }
        } catch (e: Throwable) {
            // 忽略, 走下面的兜底
        }
        return uri.toString()
    }

    private fun findRealPath(fd: Int): String? {
        return try {
            val path = File("/proc/self/fd/$fd").canonicalPath
            if (!path.startsWith("/proc") && File(path).canRead()) path else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun playlistStep(delta: Int) {
        if (videoUris.isEmpty()) return
        currentIndex = (currentIndex + delta + videoUris.size) % videoUris.size
        loadCurrentFile()
    }

    private fun loadCurrentFile() {
        val uri = videoUris[currentIndex]
        val realPath = resolveUri(uri)
        if (realPath == null) {
            showCrashDialog(IllegalStateException("Cannot resolve: $uri"))
            return
        }
        currentRealPath = realPath
        subtitles.onNewFile()
        binding.playPauseButton.setImageResource(R.drawable.exo_icon_pause)
        binding.textFileName.text = File(realPath).name
        binding.videoPlayer.loadUrl(realPath)
        MPVLib.setPropertyBoolean("pause", false)
        isPlaying = true
        firstPlay = true
    }

    private fun togglePlayPause() {
        isPlaying = !isPlaying
        MPVLib.setPropertyBoolean("pause", !isPlaying)
        binding.playPauseButton.setImageResource(
            if (isPlaying) R.drawable.exo_icon_pause else R.drawable.exo_icon_play
        )
    }

    private fun formatTime(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "00:00"
        val totalSec = seconds.roundToInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun updateProgressUi() {
        subtitles.onTick()
        val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0

        if (!isUserSeeking) {
            binding.textPosition.text = formatTime(position)
            binding.textDuration.text = formatTime(duration)
            if (duration > 0) {
                binding.seekBar.progress = ((position / duration) * 1000).toInt().coerceIn(0, 1000)
            }
        }

        // mpv 用的是 keep-open, 播完不会自动跳下一个, 这里手动检测接近结尾就切下一个
        if (duration > 0 && position >= duration - 0.5 && isPlaying && videoUris.size > 1) {
            playlistStep(1)
        }

        if (firstPlay && autoFit) {
            val w = MPVLib.getPropertyInt("video-params/w") ?: 0
            val h = MPVLib.getPropertyInt("video-params/h") ?: 0
            if (w > 0 && h > 0) {
                requestedOrientation = if (w < h)
                    ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else
                    ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                firstPlay = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        binding.videoPlayer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeSeekStartX = event.x
                    swipeSeekStartY = event.y
                    swipeSeekStartPositionSec = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                    isSwipeSeeking = false
                    isLongPressSpeeding = false

                    longPressRunnable = Runnable {
                        if (!isSwipeSeeking) {
                            isLongPressSpeeding = true
                            MPVLib.setPropertyDouble("speed", longPressSpeedMultiplier)
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, longPressTimeoutMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - swipeSeekStartX
                    val dy = event.y - swipeSeekStartY
                    if (!isSwipeSeeking && abs(dx) > swipeSeekThresholdPx && abs(dx) > abs(dy)) {
                        isSwipeSeeking = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        toggleControlsVisibility(true)
                    }
                    if (isSwipeSeeking) {
                        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                        if (duration > 0) {
                            val deltaSec = (dx / view.width) * swipeSeekFullWidthSec
                            val target = (swipeSeekStartPositionSec + deltaSec).coerceIn(0.0, duration)
                            MPVLib.command(arrayOf("no-osd", "seek", target.toString(), "absolute+keyframes"))
                            val diffSec = (target - swipeSeekStartPositionSec).roundToInt()
                            binding.gestureText.text = "${prettyTime(target.roundToInt())}\n[${prettyTime(diffSec, true)}]"
                            binding.gestureText.visibility = View.VISIBLE
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    binding.gestureText.visibility = View.GONE
                    val wasSeeking = isSwipeSeeking
                    val wasSpeeding = isLongPressSpeeding
                    if (wasSpeeding) {
                        MPVLib.setPropertyDouble("speed", 1.0)
                    }
                    if (!wasSeeking && !wasSpeeding) {
                        val visible = binding.bottomBar.visibility == View.VISIBLE
                        toggleControlsVisibility(!visible)
                    }
                    isSwipeSeeking = false
                    isLongPressSpeeding = false
                    wasSeeking || wasSpeeding
                }
                else -> false
            }
        }
    }

    private fun prettyTime(d: Int, sign: Boolean = false): String {
        if (sign) return (if (d >= 0) "+" else "-") + prettyTime(abs(d))
        val hours = d / 3600
        val minutes = d % 3600 / 60
        val seconds = d % 60
        return if (hours == 0) "%02d:%02d".format(minutes, seconds) else "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun toggleControlsVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.topBar.visibility = v
        binding.bottomBar.visibility = v
        if (visible) showPartialSystemUi() else hideSystemUi()
        // 系统栏显示/隐藏会改变 insets(导航栏可能变成手势条), 重新请求一次保证 padding 正确
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showPartialSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            // 退出时立刻切断音频, 不要等到 onDestroy() 才停(那时退出动画已经放完,
            // 缓冲区里剩的音频会被继续播出来, 听起来就像还在正常放声音)
            try {
                MPVLib.setPropertyInt("volume", 0)
                MPVLib.command(arrayOf("stop"))
            } catch (e: Throwable) {
                // 忽略, 真正的清理在 onDestroy() 里做
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        binding.videoPlayer.destroy()
    }
}

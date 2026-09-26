package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import androidx.core.net.toUri
import kotlin.math.abs


@UnstableApi
class VideoPlayerFragment : BaseFragment() {

    companion object {
        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"

        // How many ms the drag-seek gesture covers across the full screen
        // width - drag from one edge to the other seeks ~90s.
        private const val SEEK_RANGE_MS = 90_000L
        private const val LONG_PRESS_SPEED = 3.0f

        // Minimum gap between real seekTo() calls while dragging. Without
        // this, onScroll (fired dozens of times/sec) issues a new precise
        // seek on every pixel of movement, and each one has to decode
        // forward from the last keyframe - on downloaded/remuxed files
        // (large keyframe intervals) this floods the decoder and the video
        // visibly stalls/stutters while audio keeps going.
        private const val DRAG_SEEK_THROTTLE_MS = 120L
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var okHttpClient: OkHttpProxyClient

    private lateinit var player: ExoPlayer

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var dataBinding: FragmentPlayerBinding
    private var isFullscreen = false

    // ---- gesture state ----
    private var normalSpeed = 1.0f
    private var isLongPressSpeedActive = false
    private var isDraggingSeek = false
    private var dragStartPositionMs = 0L
    private var lastDragSeekAtMs = 0L
    private var pendingDragTargetMs = 0L
    private var areControlsShown = true
    private lateinit var gestureDetector: GestureDetector

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        videoPlayerViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoPlayerViewModel::class.java]
        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false)

        arguments?.getString(VIDEO_HEADERS)?.let { rawHeaders ->
            try {
                val headers =
                    Json.parseToJsonElement(rawHeaders).jsonObject.mapValues { (_, value) ->
                        value.toString().removeSurrounding("\"")
                    }
                videoPlayerViewModel.videoHeaders.set(headers)
            } catch (_: Throwable) {
                videoPlayerViewModel.videoHeaders.set(emptyMap())
            }
        }
        arguments?.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }

        val videoUrlStr = arguments?.getString(VIDEO_URL)
        if (videoUrlStr.isNullOrEmpty()) {
            Toast.makeText(context, "Invalid Video URL", Toast.LENGTH_SHORT).show()
            handleClose()
            return dataBinding.root
        }

        val iUrl = try {
            val parsedUri = videoUrlStr.toUri()
            if (parsedUri.scheme == null) {
                throw Exception("Invalid URI scheme")
            }
            parsedUri
        } catch (_: Throwable) {
            Toast.makeText(context, "Malformed Video URL", Toast.LENGTH_SHORT).show()
            handleClose()
            return dataBinding.root
        }
        videoPlayerViewModel.videoUrl.set(iUrl)

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
        // The "Cookie" header will be passed here, but OkHttp using CookieJar
        val headers = videoPlayerViewModel.videoHeaders.get() ?: emptyMap()

        val mediaFactory = createMediaFactory(headers, url.toString().startsWith("http"))

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(createRenderFactory())
            .setMediaSourceFactory(mediaFactory)
            .build()
        // Seek to the nearest keyframe instead of decoding forward to the
        // exact frame. Downloaded/remuxed files often have sparse keyframes,
        // so exact seeking is slow and, combined with rapid seeks while
        // dragging, causes visible stutter.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)

        dataBinding.apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.toolbar.setNavigationOnClickListener(navigationIconClickListener)
            currentBinding.videoView.player = player
            currentBinding.videoView.setShowBuffering(SHOW_BUFFERING_ALWAYS)
            currentBinding.videoView.setFullscreenButtonClickListener {
                toggleFullscreen()
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else {
                        currentBinding.loadingBar.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (videoPlayerViewModel.videoUrl.get().toString().startsWith("http")) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Download Only")
                            .setMessage("This video supports only download.")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                handleClose()
                            }
                            .show()
                    }
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                }
            })

            if (url != Uri.EMPTY) {
                try {
                    val mediaItem: MediaItem = MediaItem.fromUri(url)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                } catch (e: Throwable) {
                    Toast.makeText(context, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
                    handleClose()
                }
            }

            setupGestures()
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPressed()
        handlePlayerEvents()
        videoPlayerViewModel.start()
        getActivity(context)?.let { appUtil.hideSystemUI(it.window, dataBinding.root) }
    }

    private fun getActivity(context: Context?): Activity? {
        if (context == null) {
            return null
        } else if (context is ContextWrapper) {
            return if (context is Activity) {
                context
            } else {
                getActivity(context.baseContext)
            }
        }
        return null
    }

    override fun onDestroyView() {
        getActivity(context)?.let {
            appUtil.showSystemUI(it.window, dataBinding.root)
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        videoPlayerViewModel.stop()
        player.release()
        super.onDestroyView()
    }

    private val navigationIconClickListener = View.OnClickListener {
        if (isFullscreen) {
            toggleFullscreen()
        } else {
            handleClose()
        }
    }

    private fun handlePlayerEvents() {
        videoPlayerViewModel.stopPlayerEvent.observe(viewLifecycleOwner) {
            player.stop()
        }
    }

    private fun createRenderFactory(): RenderersFactory {
        return DefaultRenderersFactory(requireContext().applicationContext)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
    }

    private fun createMediaFactory(
        headers: Map<String, String>,
        isHttp: Boolean
    ): DefaultMediaSourceFactory {
        val dataSourceFactory: DataSource.Factory = if (isHttp) {
            OkHttpDataSource.Factory(okHttpClient.getProxyOkHttpClient())
                .setDefaultRequestProperties(headers)
        } else {
            DefaultDataSource.Factory(requireContext())
        }

        return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(dataSourceFactory)
    }

    private fun handleBackPressed() {
        this.view?.isFocusableInTouchMode = true
        this.view?.requestFocus()
        this.view?.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (isFullscreen) {
                    toggleFullscreen()
                } else {
                    handleClose()
                }
                true
            } else false
        }
    }

    private fun handleClose() {
        videoPlayerViewModel.stop()
        activity?.finish()
    }

    /**
     * Real fullscreen: rotates to landscape, fills the entire screen with
     * the video (zoom-fit so no letterboxing bars), hides the toolbar and
     * system bars. This overrides the app-wide portrait lock while this
     * screen is open - VideoPlayerActivity isn't pinned to a fixed
     * orientation in the manifest, so this is safe.
     */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val activity = getActivity(context) ?: return

        if (isFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            dataBinding.toolbar.visibility = View.GONE
            appUtil.hideSystemUI(activity.window, dataBinding.root)
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            dataBinding.toolbar.visibility = View.VISIBLE
            appUtil.hideSystemUI(activity.window, dataBinding.root)
        }
    }

    /**
     * Long-press anywhere = temporary 3x speed (released on lift).
     * Horizontal drag anywhere = seek relative to drag distance, shown live,
     * committed on release. Both live on gesture_overlay, which sits on top
     * of the PlayerView so its own tap-to-show-controls behaviour still
     * works via onSingleTapConfirmed forwarding.
     *
     * 注意：当 PlayerView 的控制器完全显示时，让触摸事件穿透到下层
     * PlayerView，这样全屏/暂停/进度条等自带按钮才能正常响应点击；
     * 否则 gesture_overlay 会拦截掉这些点击。
     */
    private fun setupGestures() {
        gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    areControlsShown = !areControlsShown
                    if (areControlsShown) {
                        dataBinding.videoView.showController()
                    } else {
                        dataBinding.videoView.hideController()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isDraggingSeek) return
                    isLongPressSpeedActive = true
                    normalSpeed = player.playbackParameters.speed
                    player.setPlaybackSpeed(LONG_PRESS_SPEED)
                    dataBinding.gestureIndicatorText.text = "${LONG_PRESS_SPEED}x speed"
                    dataBinding.gestureIndicatorText.visibility = View.VISIBLE
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (isLongPressSpeedActive) return false
                    // Only treat clearly-horizontal drags as seeking, so
                    // this doesn't fight with vertical swipes elsewhere.
                    if (!isDraggingSeek && abs(distanceX) < abs(distanceY)) return false

                    if (!isDraggingSeek) {
                        isDraggingSeek = true
                        dragStartPositionMs = player.currentPosition
                    }

                    val width = dataBinding.gestureOverlay.width.takeIf { it > 0 } ?: return true
                    val totalDx = (e2.x - (e1?.x ?: e2.x))
                    val offsetMs = (totalDx / width) * SEEK_RANGE_MS
                    val targetMs = (dragStartPositionMs + offsetMs.toLong())
                        .coerceIn(0, player.duration.coerceAtLeast(0))

                    val diffSeconds = (targetMs - dragStartPositionMs) / 1000
                    val sign = if (diffSeconds >= 0) "+" else ""
                    dataBinding.gestureIndicatorText.text = "$sign${diffSeconds}s"
                    dataBinding.gestureIndicatorText.visibility = View.VISIBLE

                    // Always remember where the finger currently is, but only
                    // actually issue a seek every DRAG_SEEK_THROTTLE_MS - see
                    // constant comment for why unthrottled seeking stutters.
                    pendingDragTargetMs = targetMs
                    val now = System.currentTimeMillis()
                    if (now - lastDragSeekAtMs >= DRAG_SEEK_THROTTLE_MS) {
                        lastDragSeekAtMs = now
                        player.seekTo(targetMs)
                    }
                    return true
                }
            }
        )

        dataBinding.gestureOverlay.setOnTouchListener { v, event ->
            // 控制器显示时，让触摸穿透到下层 PlayerView，
            // 这样全屏/暂停/进度条等自带按钮才能收到点击。
            if (dataBinding.videoView.isControllerFullyVisible &&
                (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP)
            ) {
                return@setOnTouchListener false
            }

            gestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (isLongPressSpeedActive) {
                    isLongPressSpeedActive = false
                    player.setPlaybackSpeed(normalSpeed)
                }
                if (isDraggingSeek) {
                    // Land on the exact spot the finger was released at -
                    // throttling above may have skipped the last position.
                    player.seekTo(pendingDragTargetMs)
                }
                isDraggingSeek = false
                dataBinding.gestureIndicatorText.visibility = View.GONE
                v.performClick()
            }
            true
        }
    }

}

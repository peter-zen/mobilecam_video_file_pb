package top.tinyai.camvideoplayback

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.icatchtek.pancam.customer.ICatchIPancamControl
import com.icatchtek.pancam.customer.ICatchIPancamListener
import com.icatchtek.pancam.customer.ICatchIPancamVideoPlayback
import com.icatchtek.pancam.customer.ICatchPancamSession
import com.icatchtek.pancam.customer.surface.ICatchSurfaceContext
import com.icatchtek.pancam.customer.type.ICatchGLEvent
import com.icatchtek.pancam.customer.type.ICatchGLEventID
import com.icatchtek.pancam.customer.type.ICatchGLColor
import com.icatchtek.pancam.customer.type.ICatchGLDisplayPPI
import com.icatchtek.reliant.customer.transport.ICatchINETTransport
import com.icatchtek.reliant.customer.type.ICatchFile
import com.icatchtek.reliant.customer.type.ICatchFileType
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PlaybackActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var progressBar: ProgressBar
    private lateinit var controlBar: LinearLayout
    private lateinit var playPauseBtn: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeText: TextView

    private var session: ICatchPancamSession? = null
    private var videoPlayback: ICatchIPancamVideoPlayback? = null
    private var pancamControl: ICatchIPancamControl? = null
    private var surface: Surface? = null
    private var surfaceReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isPlaying = false
    private var isPaused = false
    private var hasStarted = false
    private var totalDuration = 0.0
    private var currentPosition = 0.0
    private var controlsVisible = false
    private var isSeeking = false
    private var cameraIp: String? = null
    private var videoFileName: String? = null
    private var isReleased = false

    private val hideControlsRunnable = Runnable { hideControls() }
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            mainHandler.postDelayed(this, 500)
        }
    }

    private val streamStateListener = object : ICatchIPancamListener {
        override fun eventNotify(event: ICatchGLEvent) {
            when (event.eventID) {
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS -> {
                    currentPosition = event.doubleValue1
                    if (!isSeeking) {
                        mainHandler.post {
                            if (!isReleased && totalDuration > 0) {
                                val progress = (currentPosition / totalDuration * 1000).toInt()
                                seekBar.progress = progress.coerceIn(0, 1000)
                                updateTimeText(currentPosition, totalDuration)
                            }
                        }
                    }
                }
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED -> {
                    mainHandler.post {
                        if (!isReleased) {
                            isPlaying = false
                            isPaused = false
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                            mainHandler.removeCallbacks(updateProgressRunnable)
                        }
                    }
                }
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED -> {
                    val state = event.longValue1.toInt()
                    mainHandler.post {
                        if (!isReleased) {
                            when (state) {
                                1 -> progressBar.visibility = View.VISIBLE
                                2 -> progressBar.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_FILENAME = "filename"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        cameraIp = intent.getStringExtra(EXTRA_IP)
        videoFileName = intent.getStringExtra(EXTRA_FILENAME)

        if (cameraIp.isNullOrBlank() || videoFileName.isNullOrBlank()) {
            showErrorAndFinish("Missing IP or filename")
            return
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surface = holder.surface
                    surfaceReady = true
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    surface = holder.surface
                    surfaceWidth = width
                    surfaceHeight = height
                    if (!hasStarted && surfaceReady) {
                        hasStarted = true
                        executor.execute { startPlayback(cameraIp!!, videoFileName!!) }
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surface = null
                    surfaceReady = false
                }
            })
            setOnClickListener { toggleControls() }
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val videoHeight = (screenWidth * 9.0 / 16.0).toInt()
        rootLayout.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                videoHeight,
                Gravity.CENTER
            )
        )

        // Loading spinner
        progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
        }
        rootLayout.addView(
            progressBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // Control bar (play/pause, seek, time)
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        playPauseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(0x00000000)
            setColorFilter(0xFFFFFFFF.toInt())
            setOnClickListener { togglePlayPause() }
        }
        controlBar.addView(playPauseBtn, LinearLayout.LayoutParams(96, 96))

        seekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && totalDuration > 0) {
                        val seekPos = progress / 1000.0 * totalDuration
                        updateTimeText(seekPos, totalDuration)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = true
                    mainHandler.removeCallbacks(hideControlsRunnable)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = false
                    if (totalDuration > 0) {
                        val seekPos = seekBar!!.progress / 1000.0 * totalDuration
                        executor.execute {
                            try {
                                videoPlayback?.seek(seekPos)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    scheduleHideControls()
                }
            })
        }
        controlBar.addView(
            seekBar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        controlBar.addView(timeText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 16 })

        rootLayout.addView(
            controlBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(rootLayout)
    }

    private fun startPlayback(ip: String, fileName: String) {
        try {
            val transport = ICatchINETTransport(ip)
            session = ICatchPancamSession.createSession()

            val metrics = resources.displayMetrics
            val ppi = ICatchGLDisplayPPI(metrics.xdpi, metrics.ydpi)
            val prepared = session?.prepareSession(transport, ICatchGLColor.BLACK, ppi) ?: false

            if (!prepared) {
                showErrorAndFinish("Failed to prepare session")
                return
            }

            videoPlayback = session?.videoPlayback
            if (videoPlayback == null) {
                showErrorAndFinish("Failed to get video playback")
                return
            }

            pancamControl = session?.getControl()
            try {
                pancamControl?.addEventListener(
                    ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS,
                    streamStateListener
                )
                pancamControl?.addEventListener(
                    ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED,
                    streamStateListener
                )
                pancamControl?.addEventListener(
                    ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED,
                    streamStateListener
                )
            } catch (_: Exception) {
            }

            val currentSurface = surface
            if (currentSurface == null || !currentSurface.isValid) {
                showErrorAndFinish("Surface not ready")
                return
            }

            val surfaceContext = ICatchSurfaceContext(currentSurface)
            val renderEnabled = videoPlayback?.enableRender(surfaceContext) ?: false
            if (!renderEnabled) {
                showErrorAndFinish("Failed to enable render")
                return
            }

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                try {
                    surfaceContext.setViewPort(0, 0, surfaceWidth, surfaceHeight)
                } catch (_: Exception) {
                }
            }

            val filePath = "/DCIM/$fileName"
            val iCatchFile = ICatchFile(
                0,
                ICatchFileType.ICH_FILE_TYPE_VIDEO,
                filePath,
                fileName,
                0,
                "",
                0.0,
                0,
                0,
                0,
                0
            )

            val playResult = videoPlayback?.play(iCatchFile, true, true) ?: false
            if (!playResult) {
                showErrorAndFinish("Failed to start playback")
                return
            }

            val resumeResult = videoPlayback?.resume() ?: false
            if (!resumeResult) {
                showErrorAndFinish("Failed to resume playback")
                return
            }

            isPlaying = true
            isPaused = false
            totalDuration = videoPlayback?.getLength() ?: 0.0

            mainHandler.post {
                if (isReleased) return@post
                showControls()
                updateTimeText(0.0, totalDuration)
                mainHandler.post(updateProgressRunnable)
            }
        } catch (e: Exception) {
            showErrorAndFinish("Playback error: ${e.message}")
        }
    }

    private fun togglePlayPause() {
        executor.execute {
            try {
                if (isPaused) {
                    videoPlayback?.resume()
                    isPaused = false
                    mainHandler.post {
                        if (!isReleased) {
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                            mainHandler.post(updateProgressRunnable)
                        }
                    }
                } else {
                    videoPlayback?.pause()
                    isPaused = true
                    mainHandler.post {
                        if (!isReleased) {
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                            mainHandler.removeCallbacks(updateProgressRunnable)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        if (isReleased) return
        controlBar.visibility = View.VISIBLE
        controlsVisible = true
        scheduleHideControls()
    }

    private fun hideControls() {
        controlBar.visibility = View.GONE
        controlsVisible = false
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun updateProgress() {
        // Progress is now driven by SDK events (ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS).
        // This runnable remains as a fallback to keep UI refreshing.
        if (!isSeeking && totalDuration > 0) {
            val progress = (currentPosition / totalDuration * 1000).toInt()
            seekBar.progress = progress.coerceIn(0, 1000)
            updateTimeText(currentPosition, totalDuration)
        }
    }

    private fun updateTimeText(current: Double, total: Double) {
        timeText.text = "${formatTime(current)} / ${formatTime(total)}"
    }

    private fun formatTime(seconds: Double): String {
        val totalSec = seconds.toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun release() {
        if (isReleased) return
        isReleased = true
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        try {
            pancamControl?.removeEventListener(
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS,
                streamStateListener
            )
        } catch (_: Exception) {
        }
        try {
            pancamControl?.removeEventListener(
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED,
                streamStateListener
            )
        } catch (_: Exception) {
        }
        try {
            pancamControl?.removeEventListener(
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED,
                streamStateListener
            )
        } catch (_: Exception) {
        }
        try {
            videoPlayback?.pause()
        } catch (_: Exception) {
        }
        try {
            videoPlayback?.stop()
        } catch (_: Exception) {
        }
        try {
            session?.destroySession()
        } catch (_: Exception) {
        }
        isPlaying = false
        executor.shutdown()
    }

    private fun showErrorAndFinish(message: String) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onBackPressed() {
        release()
        super.onBackPressed()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}

package top.tinyai.camvideoplayback

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.icatchtek.pancam.customer.ICatchPancamLog
import com.icatchtek.pancam.customer.ICatchIPancamControl
import com.icatchtek.pancam.customer.ICatchIPancamListener
import com.icatchtek.pancam.customer.ICatchIPancamVideoPlayback
import com.icatchtek.pancam.customer.ICatchPancamSession
import com.icatchtek.pancam.customer.surface.ICatchSurfaceContext
import com.icatchtek.pancam.customer.type.ICatchGLEvent
import com.icatchtek.pancam.customer.type.ICatchGLEventID
import com.icatchtek.pancam.customer.type.ICatchGLColor
import com.icatchtek.pancam.customer.type.ICatchGLDisplayPPI
import com.icatchtek.pancam.customer.type.ICatchGLLogType
import com.icatchtek.reliant.customer.transport.ICatchINETTransport
import com.icatchtek.reliant.customer.type.ICatchFile
import com.icatchtek.reliant.customer.type.ICatchFileType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var seekTargetPosition = -1.0
    private var cameraIp: String? = null
    private var videoFileName: String? = null
    private var isReleased = false
    private val appLogLock = Any()
    private var appLogFile: File? = null
    private var lastPtsEventElapsedMs = -1L

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
                    val pts = event.doubleValue1
                    val nowElapsedMs = SystemClock.elapsedRealtime()
                    val deltaMs = if (lastPtsEventElapsedMs < 0) -1 else nowElapsedMs - lastPtsEventElapsedMs
                    lastPtsEventElapsedMs = nowElapsedMs
                    logApp(
                        "D",
                        "event pts=$pts, thread=${Thread.currentThread().name}, deltaMs=$deltaMs, " +
                            "isSeeking=$isSeeking, currentPosition=$currentPosition, seekTarget=$seekTargetPosition"
                    )
                    if (isSeeking) {
                        val jumpedToTarget = seekTargetPosition >= 0 && kotlin.math.abs(pts - seekTargetPosition) < 2.0
                        val bigJump = kotlin.math.abs(pts - currentPosition) > 3.0
                        if (jumpedToTarget || bigJump) {
                            isSeeking = false
                            seekTargetPosition = -1.0
                            logApp("D", "seek accepted: jumpedToTarget=$jumpedToTarget, bigJump=$bigJump")
                        } else {
                            // 还是旧位置，忽略事件，保持seek bar在用户拖动的位置
                            currentPosition = pts
                            return
                        }
                    }
                    currentPosition = pts
                    mainHandler.post {
                        if (!isReleased && totalDuration > 0) {
                            val progress = (currentPosition / totalDuration * 1000).toInt()
                            seekBar.progress = progress.coerceIn(0, 1000)
                            updateTimeText(currentPosition, totalDuration)
                        }
                    }
                }
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED -> {
                    logApp("I", "event ended, currentPosition=$currentPosition, thread=${Thread.currentThread().name}")
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
                    logApp(
                        "I",
                        "event caching state=$state, currentPosition=$currentPosition, thread=${Thread.currentThread().name}"
                    )
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
        private const val TAG = "PlaybackActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppLogging()
        configureSdkLogging()

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        cameraIp = intent.getStringExtra(EXTRA_IP)
        videoFileName = intent.getStringExtra(EXTRA_FILENAME)
        logApp("I", "onCreate ip=$cameraIp, file=$videoFileName")

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
                    logApp(
                        "I",
                        "surfaceCreated valid=${holder.surface?.isValid == true}, " +
                            "thread=${Thread.currentThread().name}"
                    )
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
                    logApp(
                        "I",
                        "surfaceChanged format=$format, width=$width, height=$height, " +
                            "valid=${holder.surface?.isValid == true}, hasStarted=$hasStarted"
                    )
                    if (!hasStarted && surfaceReady) {
                        hasStarted = true
                        executor.execute { startPlayback(cameraIp!!, videoFileName!!) }
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surface = null
                    surfaceReady = false
                    logApp("W", "surfaceDestroyed thread=${Thread.currentThread().name}")
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
            visibility = View.VISIBLE
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
                    logApp("D", "seek start: progress=${seekBar?.progress}, isSeeking=true")
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val progress = seekBar?.progress ?: 0
                    if (totalDuration > 0) {
                        val seekPos = progress / 1000.0 * totalDuration
                        seekTargetPosition = seekPos
                        logApp("D", "seek stop: progress=$progress, target=$seekPos, isSeeking stays true")
                        executor.execute {
                            try {
                                logApp("D", "calling seek($seekPos)")
                                videoPlayback?.seek(seekPos)
                                logApp("D", "seek returned")
                            } catch (e: Exception) {
                                logApp("E", "seek exception: ${e.message}", e)
                                // seek失败，放开锁定
                                mainHandler.post {
                                    isSeeking = false
                                    seekTargetPosition = -1.0
                                }
                            }
                        }
                    }
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

        val marginBottomPx = (48 * resources.displayMetrics.density).toInt()
        rootLayout.addView(
            controlBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = marginBottomPx
            }
        )

        setContentView(rootLayout)
    }

    private fun configureAppLogging() {
        try {
            val logDir = filesDir.resolve("applogs")
            logDir.mkdirs()
            appLogFile = logDir.resolve("playback_debug_${System.currentTimeMillis()}.log")
            logApp("I", "App logging enabled, filePath=${appLogFile?.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable app file logging: ${e.message}")
        }
    }

    private fun configureSdkLogging() {
        try {
            val logger = ICatchPancamLog.getInstance()
            logger.setSystemLogOutput(true)
            logger.setDebugMode(true)
            logger.setLog(ICatchGLLogType.ICH_GL_LOG_TYPE_STREAM, true)
            logger.setLog(ICatchGLLogType.ICH_GL_LOG_TYPE_COMMON, true)
            logger.setLog(ICatchGLLogType.ICH_GL_LOG_TYPE_DEVELOP, true)
            logger.setLog(ICatchGLLogType.ICH_GL_LOG_TYPE_OPENGL, true)

            val logDir = filesDir.resolve("sdklogs")
            logDir.mkdirs()
            logger.setFileLogPath(logDir.absolutePath)
            logger.setFileLogOutput(true)
            logApp("I", "SDK logging enabled, fileLogPath=${logDir.absolutePath}")
        } catch (e: Exception) {
            logApp("W", "Failed to enable SDK logging: ${e.message}", e)
        }
    }

    private fun startPlayback(ip: String, fileName: String) {
        try {
            logApp("I", "startPlayback begin ip=$ip, file=$fileName, thread=${Thread.currentThread().name}")
            val transport = ICatchINETTransport(ip)
            session = ICatchPancamSession.createSession()
            logApp("I", "session created=${session != null}")

            val metrics = resources.displayMetrics
            val ppi = ICatchGLDisplayPPI(metrics.xdpi, metrics.ydpi)
            val prepared = session?.prepareSession(transport, ICatchGLColor.BLACK, ppi) ?: false
            logApp("I", "prepareSession result=$prepared")

            if (!prepared) {
                showErrorAndFinish("Failed to prepare session")
                return
            }

            videoPlayback = session?.videoPlayback
            logApp("I", "videoPlayback ready=${videoPlayback != null}")
            if (videoPlayback == null) {
                showErrorAndFinish("Failed to get video playback")
                return
            }

            pancamControl = session?.getControl()
            logApp("I", "pancamControl ready=${pancamControl != null}")
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
                logApp("I", "event listeners registered")
            } catch (e: Exception) {
                logApp("W", "failed to register event listeners: ${e.message}", e)
            }

            val currentSurface = surface
            logApp(
                "I",
                "surface check ready=${currentSurface != null}, valid=${currentSurface?.isValid == true}, " +
                    "width=$surfaceWidth, height=$surfaceHeight"
            )
            if (currentSurface == null || !currentSurface.isValid) {
                showErrorAndFinish("Surface not ready")
                return
            }

            val surfaceContext = ICatchSurfaceContext(currentSurface)
            val renderEnabled = videoPlayback?.enableRender(surfaceContext) ?: false
            logApp("I", "enableRender result=$renderEnabled")
            if (!renderEnabled) {
                showErrorAndFinish("Failed to enable render")
                return
            }

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                try {
                    surfaceContext.setViewPort(0, 0, surfaceWidth, surfaceHeight)
                    logApp("I", "setViewPort width=$surfaceWidth, height=$surfaceHeight")
                } catch (e: Exception) {
                    logApp("W", "setViewPort failed: ${e.message}", e)
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

            val playResult = videoPlayback?.play(iCatchFile, true, true, 3.0) ?: false
            logApp("I", "play result=$playResult, filePath=$filePath")
            if (!playResult) {
                showErrorAndFinish("Failed to start playback")
                return
            }

            val resumeResult = videoPlayback?.resume() ?: false
            logApp("I", "resume result=$resumeResult")
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
            logApp("I", "startPlayback end totalDuration=$totalDuration")
        } catch (e: Exception) {
            logApp("E", "startPlayback exception: ${e.message}", e)
            showErrorAndFinish("Playback error: ${e.message}")
        }
    }

    private fun togglePlayPause() {
        executor.execute {
            try {
                if (isPaused) {
                    val result = videoPlayback?.resume()
                    logApp("I", "togglePlayPause resume result=$result")
                    isPaused = false
                    mainHandler.post {
                        if (!isReleased) {
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                            mainHandler.post(updateProgressRunnable)
                        }
                    }
                } else {
                    videoPlayback?.pause()
                    logApp("I", "togglePlayPause pause called")
                    isPaused = true
                    mainHandler.post {
                        if (!isReleased) {
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                            mainHandler.removeCallbacks(updateProgressRunnable)
                        }
                    }
                }
            } catch (e: Exception) {
                logApp("E", "togglePlayPause exception: ${e.message}", e)
            }
        }
    }

    private fun toggleControls() {
        // Control bar is always visible; clicking surface does nothing.
    }

    private fun showControls() {
        if (isReleased) return
        controlBar.visibility = View.VISIBLE
        controlsVisible = true
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
        logApp("I", "release begin")
        isReleased = true
        mainHandler.removeCallbacks(updateProgressRunnable)
        try {
            logApp("I", "removeEventListener playing_status")
            pancamControl?.removeEventListener(
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS,
                streamStateListener
            )
        } catch (e: Exception) {
            logApp("W", "removeEventListener playing_status failed: ${e.message}", e)
        }
        try {
            logApp("I", "removeEventListener playing_ended")
            pancamControl?.removeEventListener(
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED,
                streamStateListener
            )
        } catch (e: Exception) {
            logApp("W", "removeEventListener playing_ended failed: ${e.message}", e)
        }
        try {
            logApp("I", "removeEventListener caching_changed")
            pancamControl?.removeEventListener(
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED,
                streamStateListener
            )
        } catch (e: Exception) {
            logApp("W", "removeEventListener caching_changed failed: ${e.message}", e)
        }
        try {
            logApp("I", "videoPlayback.pause()")
            videoPlayback?.pause()
        } catch (e: Exception) {
            logApp("W", "videoPlayback.pause failed: ${e.message}", e)
        }
        try {
            logApp("I", "videoPlayback.stop()")
            videoPlayback?.stop()
        } catch (e: Exception) {
            logApp("W", "videoPlayback.stop failed: ${e.message}", e)
        }
        try {
            logApp("I", "session.destroySession()")
            session?.destroySession()
        } catch (e: Exception) {
            logApp("W", "session.destroySession failed: ${e.message}", e)
        }
        isPlaying = false
        executor.shutdown()
        logApp("I", "release end")
    }

    private fun showErrorAndFinish(message: String) {
        logApp("E", "showErrorAndFinish message=$message")
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onBackPressed() {
        logApp("I", "onBackPressed")
        release()
        super.onBackPressed()
    }

    override fun onPause() {
        logApp("I", "onPause isPlaying=$isPlaying, isPaused=$isPaused, currentPosition=$currentPosition")
        super.onPause()
    }

    override fun onStop() {
        logApp("I", "onStop isPlaying=$isPlaying, isPaused=$isPaused, currentPosition=$currentPosition")
        super.onStop()
    }

    override fun onDestroy() {
        logApp("I", "onDestroy isFinishing=$isFinishing, isDestroyed=$isDestroyed")
        release()
        super.onDestroy()
    }

    private fun logApp(level: String, message: String, throwable: Throwable? = null) {
        val fullMessage = buildString {
            append(message)
            if (throwable != null) {
                append(" | exception=")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message)
            }
        }
        when (level) {
            "E" -> Log.e(TAG, fullMessage, throwable)
            "W" -> Log.w(TAG, fullMessage, throwable)
            "I" -> Log.i(TAG, fullMessage, throwable)
            else -> Log.d(TAG, fullMessage, throwable)
        }

        val targetFile = appLogFile ?: return
        val line = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append(" [")
            append(level)
            append("] [thread=")
            append(Thread.currentThread().name)
            append("] ")
            append(fullMessage)
            append('\n')
        }
        synchronized(appLogLock) {
            try {
                targetFile.appendText(line)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append app log file: ${e.message}")
            }
        }
    }
}

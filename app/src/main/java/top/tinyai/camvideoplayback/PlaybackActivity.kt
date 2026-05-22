package top.tinyai.camvideoplayback

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.icatchtek.pancam.customer.ICatchIPancamVideoPlayback
import com.icatchtek.pancam.customer.ICatchPancamSession
import com.icatchtek.pancam.customer.surface.ICatchSurfaceContext
import com.icatchtek.pancam.customer.type.ICatchGLColor
import com.icatchtek.pancam.customer.type.ICatchGLDisplayPPI
import com.icatchtek.reliant.customer.transport.ICatchINETTransport
import com.icatchtek.reliant.customer.type.ICatchFile
import com.icatchtek.reliant.customer.type.ICatchFileType
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PlaybackActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private var session: ICatchPancamSession? = null
    private var videoPlayback: ICatchIPancamVideoPlayback? = null
    private var surface: Surface? = null
    private var surfaceReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isPlaying = false
    private var hasStarted = false
    private var cameraIp: String? = null
    private var videoFileName: String? = null

    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_FILENAME = "filename"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen + portrait
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
        }

        // Layout: full width, aspect ratio height, vertically centered
        val screenWidth = resources.displayMetrics.widthPixels
        val videoHeight = (screenWidth * 9.0 / 16.0).toInt()
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            videoHeight,
            Gravity.CENTER
        )
        rootLayout.addView(surfaceView, layoutParams)
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
                    // viewport set failed, continue anyway
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
        } catch (e: Exception) {
            showErrorAndFinish("Playback error: ${e.message}")
        }
    }

    private fun stopPlayback() {
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
    }

    private fun showErrorAndFinish(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onBackPressed() {
        executor.execute { stopPlayback() }
        executor.shutdown()
        super.onBackPressed()
    }

    override fun onDestroy() {
        executor.execute { stopPlayback() }
        executor.shutdown()
        super.onDestroy()
    }
}

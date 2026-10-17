package app.muxtv.phone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline companion prototype: scans only MXLC04 public-key QR frames.
 *
 * No INTERNET permission, no pairing bearer, no source forms, no credentials and no
 * network call. A scanned key is UNVERIFIED until an independently authenticated
 * TV/phone confirmation and secure full-duplex transport are implemented.
 */
class PhoneQrPreviewActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var retry: Button
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var startingCamera = false
    private var cameraPermissionPending = false
    private var accepted = false

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionPending = false
        if (granted) startCamera()
        else showStatus("Нет разрешения на камеру. Для сканирования разрешите доступ в настройках.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildScreen()
        startCamera()
    }

    override fun onStart() {
        super.onStart()
        if (::previewView.isInitialized && !accepted) startCamera()
    }

    override fun onStop() {
        scanning.set(false)
        provider?.unbindAll()
        super.onStop()
    }

    override fun onDestroy() {
        provider?.unbindAll()
        decodeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(14, 19, 30))
        }
        val title = TextView(this).apply {
            text = "MuxTV · Проверка QR телевизора"
            textSize = 21f
            setTextColor(Color.WHITE)
        }
        val notice = TextView(this).apply {
            text = "Экспериментальный офлайн-сканер публичного ключа. " +
                "Он не подключается к ТВ и не принимает IPTV-пароли."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(12))
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Область сканирования QR-кода"
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(8))
            text = "Наведите камеру на QR публичного ключа MuxTV."
        }
        detail = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(144, 221, 196))
            setPadding(0, dp(4), 0, dp(8))
            setTextIsSelectable(false)
            contentDescription = "Fingerprint публичного ключа"
        }
        retry = Button(this).apply {
            text = "Сканировать снова"
            setOnClickListener {
                accepted = false
                detail.text = ""
                startCamera()
            }
        }
        root.addView(title)
        root.addView(notice)
        root.addView(previewView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        root.addView(detail)
        root.addView(retry)
        setContentView(root)
    }

    private fun startCamera() {
        if (!::previewView.isInitialized || accepted || isFinishing || isDestroyed ||
            lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED ||
            startingCamera || cameraPermissionPending || scanning.get() || decodeExecutor.isShutdown
        ) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showStatus("Для считывания QR необходим доступ к камере.")
            cameraPermissionPending = true
            permission.launch(Manifest.permission.CAMERA)
            return
        }

        startingCamera = true
        showStatus("Наведите камеру на QR формата MXLC04:1, показанный телевизором.")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            startingCamera = false
            if (accepted || isFinishing || isDestroyed || decodeExecutor.isShutdown ||
                lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED
            ) return@addListener

            try {
                val camera = future.get()
                provider = camera
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(decodeExecutor) { frame -> processFrame(frame) }
                camera.unbindAll()
                camera.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
                scanning.set(true)
            } catch (_: Exception) {
                scanning.set(false)
                showStatus("Камера недоступна. Проверьте разрешение и повторите попытку.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        try {
            if (!scanning.get()) return
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0 || width.toLong() * height > 4_194_304) return
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer.duplicate()
            val stride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (stride <= 0 || pixelStride <= 0) return
            val luma = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = y.toLong() * stride + x.toLong() * pixelStride
                    if (offset >= buffer.limit()) return
                    luma[y * width + x] = buffer.get(offset.toInt())
                }
            }
            val text = QrFrameDecoder.decode(
                luma, width, height, image.imageInfo.rotationDegrees,
            ) ?: return
            when (val result = TvQrVerification.verify(text)) {
                is TvQrVerification.Result.PublicKeyPreview -> {
                    if (scanning.compareAndSet(true, false)) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                accepted = true
                                provider?.unbindAll()
                                showStatus("Публичный ключ считан. Подлинность ТВ НЕ подтверждена.")
                                detail.text = "Отпечаток ключа SHA-256:\n" +
                                    TvQrVerification.formattedFingerprint(result.fingerprint) +
                                    "\n\nНе вводите IPTV-пароли. Защищённое сопряжение ещё не подключено."
                            }
                        }
                    }
                }
                TvQrVerification.Result.Rejected -> {
                    // Other URLs and arbitrary QR payloads are silently ignored.
                    // Do not send their contents to logs, browser, server or analytics.
                }
            }
        } catch (_: Exception) {
            // A malformed camera frame or QR is not an application crash.
        } finally {
            image.close()
        }
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

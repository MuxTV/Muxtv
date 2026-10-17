package app.muxtv.phone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKey
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Scan-only Android phone companion entry point.
 *
 * No INTERNET permission, browser, external QR decoder, provider input or persisted
 * pairing key. Scanning a public key is NOT the same as authenticating the real TV.
 */
class PhoneQrActivity : ComponentActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean(false)
    private val scanEpoch = AtomicLong(0)
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanned: TvQrPublicKey? = null
    private var phoneSigningIdentity: PhoneSigningIdentity? = null
    private var invalidNoticeSent = false

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var retryButton: Button

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCameraIfAllowed()
        else status.setText(R.string.scan_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(Color.rgb(248, 249, 251))
        }
        val title = TextView(this).apply {
            text = getString(R.string.scan_title)
            textSize = 23f
            setTextColor(Color.rgb(20, 34, 50))
            setPadding(0, 0, 0, dp(10))
        }
        val introduction = TextView(this).apply {
            setText(R.string.scan_intro)
            textSize = 15f
            setTextColor(Color.rgb(45, 55, 68))
        }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = getString(R.string.scan_title)
        }
        status = TextView(this).apply {
            setText(R.string.scan_permission)
            textSize = 15f
            setTextColor(Color.rgb(34, 54, 72))
        }
        details = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(20, 45, 70))
            visibility = View.GONE
        }
        retryButton = Button(this).apply {
            setText(R.string.scan_permission_button)
            setOnClickListener {
                if (scanned != null) {
                    phoneSigningIdentity?.close()
                    phoneSigningIdentity = null
                    scanned = null
                    scanEpoch.incrementAndGet()
                    delivered.set(false)
                    invalidNoticeSent = false
                    details.visibility = View.GONE
                }
                if (hasCameraPermission()) startCameraIfAllowed()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        content.addView(title)
        content.addView(introduction)
        content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(330),
        ).apply { topMargin = dp(16); bottomMargin = dp(14) })
        content.addView(status)
        content.addView(details, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        content.addView(retryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        setContentView(content)

        if (hasCameraPermission()) retryButton.setText(R.string.scan_again)
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) startCameraIfAllowed()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCameraIfAllowed() {
        if (!hasCameraPermission() || scanned != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        status.setText(R.string.scan_busy)
        retryButton.setText(R.string.scan_again)
        val invocationEpoch = scanEpoch.get()
        val pending = ProcessCameraProvider.getInstance(this)
        pending.addListener({
            if (!hasCameraPermission() || scanned != null ||
                scanEpoch.get() != invocationEpoch ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            try {
                val provider = pending.get()
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build()
                    .also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { frame ->
                    try {
                        if (scanEpoch.get() != invocationEpoch || delivered.get()) return@setAnalyzer
                        val plane = frame.planes.firstOrNull() ?: return@setAnalyzer
                        val bytes = ByteArray(plane.buffer.remaining())
                        plane.buffer.get(bytes)
                        val value = QrLumaDecoder.decode(
                            bytes, frame.width, frame.height,
                            plane.rowStride, plane.pixelStride,
                            frame.imageInfo.rotationDegrees,
                        )
                        bytes.fill(0)
                        if (value != null) {
                            if (value.length > 900) return@setAnalyzer
                            val parsed = TvQrPublicKeyParser.parseOrNull(value)
                            if (parsed != null && delivered.compareAndSet(false, true)) {
                                runOnUiThread {
                                    if (scanEpoch.get() == invocationEpoch) showScannedKey(parsed)
                                }
                            } else if (parsed == null && !invalidNoticeSent) {
                                invalidNoticeSent = true
                                runOnUiThread { status.setText(R.string.scan_not_supported) }
                            }
                        }
                    } finally {
                        frame.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis,
                )
            } catch (_: Exception) {
                status.setText(R.string.scan_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showScannedKey(key: TvQrPublicKey) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            delivered.set(false)
            return
        }
        // No phone key or IPTV data is sent over legacy HTTP. The signing
        // identity will be used only by a future reviewed protected transport.
        val identity = try {
            PhoneSigningIdentity.generate()
        } catch (_: Exception) {
            delivered.set(false)
            status.setText(R.string.scan_signing_key_error)
            return
        }
        phoneSigningIdentity?.close()
        phoneSigningIdentity = identity
        scanned = key
        cameraProvider?.unbindAll()
        status.setText(R.string.scan_found)
        details.text = listOf(
            getString(R.string.scan_fingerprint, key.fingerprint),
            getString(R.string.scan_phone_fingerprint, identity.fingerprint),
            getString(R.string.scan_invitation, key.invitationId),
            getString(R.string.scan_pairing, key.pairingId),
            getString(R.string.scan_warning),
        ).joinToString("\n\n")
        details.visibility = View.VISIBLE
        retryButton.setText(R.string.scan_again)
    }

    override fun onStop() {
        cameraProvider?.unbindAll()
        phoneSigningIdentity?.close()
        phoneSigningIdentity = null
        // Do not retain obsolete phone key material across foreground lifecycles.
        scanned = null
        scanEpoch.incrementAndGet()
        delivered.set(false)
        if (::details.isInitialized) details.visibility = View.GONE
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdownNow()
        phoneSigningIdentity?.close()
        phoneSigningIdentity = null
        scanned = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

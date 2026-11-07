package app.muxtv.phone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * QR-pinned phone companion. The camera never opens a network connection.
 * A separate explicit button may start ONLY the native one-shot proof path
 * after scanning the actual TV key and passing the Android LAN permission gate.
 * No browser, provider fields, credentials, legacy /pair or persisted grant.
 */
class PhoneQrActivity : ComponentActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean(false)
    private val scanEpoch = AtomicLong(0)
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanned: TvQrPublicKey? = null
    private var phoneSigningIdentity: PhoneSigningIdentity? = null
    private var invalidNoticeSent = false
    private var nativeSession: PhoneNativeProofSession? = null
    private data class PendingNative(val generation: Long, val endpoint: PhoneNativeEndpoint)
    private var pendingNative: PendingNative? = null
    private lateinit var nativePanel: LinearLayout
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var startNativeButton: Button
    private lateinit var verifyNativeButton: Button
    private lateinit var mxh1PortInput: EditText
    private lateinit var mxh1Button: Button

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

    private val networkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingNative
        pendingNative = null
        if (!granted) {
            if (::status.isInitialized) status.setText(R.string.scan_native_permission_denied)
        } else if (pending != null && pending.generation == scanEpoch.get() &&
            scanned != null && phoneSigningIdentity != null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            beginNativeProof(pending.endpoint)
        }
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
                    resetScannedSession()
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
        nativePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "lc04-phone-native-panel"
            visibility = View.GONE
        }
        val nativeIntro = TextView(this).apply {
            setText(R.string.scan_native_intro)
            textSize = 14f
            setPadding(0, dp(14), 0, dp(10))
        }
        hostInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.scan_native_host)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(15))
            contentDescription = getString(R.string.scan_native_host)
        }
        portInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.scan_native_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            contentDescription = getString(R.string.scan_native_port)
        }
        startNativeButton = Button(this).apply {
            tag = "lc04-phone-native-opt-in"
            setText(R.string.scan_native_start)
            setOnClickListener { startNativeFromGesture() }
        }
        verifyNativeButton = Button(this).apply {
            tag = "lc04-phone-native-verify"
            setText(R.string.scan_native_verify)
            visibility = View.GONE
            setOnClickListener {
                if (nativeSession?.finishAfterTvButton() == true) {
                    isEnabled = false
                    status.setText(R.string.scan_native_verifying)
                }
            }
        }
        nativePanel.addView(nativeIntro)
        nativePanel.addView(hostInput)
        nativePanel.addView(portInput)
        nativePanel.addView(startNativeButton)
        nativePanel.addView(verifyNativeButton)
        mxh1PortInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.scan_mxh1_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            visibility = View.GONE
        }
        mxh1Button = Button(this).apply {
            tag = "lc04-phone-mxh1-second-opt-in"
            setText(R.string.scan_mxh1_start)
            visibility = View.GONE
            setOnClickListener { startMxH1FromGesture() }
        }
        nativePanel.addView(mxh1PortInput)
        nativePanel.addView(mxh1Button)
        content.addView(nativePanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        // Camera preview plus two long fingerprints must remain reachable on small phones.
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })

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
        // No network activity during scan. Signing key stays process-local
        // until an explicit user opt-in to the QR-pinned native proof only.
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
        nativePanel.visibility = View.VISIBLE
        startNativeButton.isEnabled = true
        hostInput.isEnabled = true
        portInput.isEnabled = true
        verifyNativeButton.visibility = View.GONE
        mxh1PortInput.visibility = View.GONE
        mxh1Button.visibility = View.GONE
    }

    /** Always a second, separate gesture after successful MXW1 proof. */
    private fun startMxH1FromGesture() {
        val session = nativeSession ?: return
        val endpoint = PhoneNativeEndpoint.parseOrNull(
            hostInput.text.toString(), mxh1PortInput.text.toString(),
        )
        if (endpoint == null) {
            status.setText(R.string.scan_native_endpoint_invalid)
            return
        }
        if (session.beginMxH1(endpoint)) {
            mxh1Button.isEnabled = false
            mxh1PortInput.isEnabled = false
            status.setText(R.string.scan_mxh1_verifying)
        } else {
            status.setText(R.string.scan_native_failed)
        }
    }

    private fun startNativeFromGesture() {
        if (nativeSession != null || scanned == null || phoneSigningIdentity == null) return
        val endpoint = PhoneNativeEndpoint.parseOrNull(
            hostInput.text.toString(), portInput.text.toString(),
        )
        if (endpoint == null) {
            status.setText(R.string.scan_native_endpoint_invalid)
            return
        }
        // Android 17 / API37+ target 37: runtime local-network permission.
        // API26..36: do not request ACCESS_LOCAL_NETWORK at runtime.
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNative = PendingNative(scanEpoch.get(), endpoint)
            networkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }
        beginNativeProof(endpoint)
    }

    private fun beginNativeProof(endpoint: PhoneNativeEndpoint) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            nativeSession != null
        ) return
        val key = scanned ?: return
        val identity = phoneSigningIdentity ?: return
        val epoch = scanEpoch.get()
        // The flow owns the signing identity and must close it on onStop.
        phoneSigningIdentity = null
        val session = PhoneNativeProofSession(
            context = this,
            scannedTv = key,
            signingPhone = identity,
            postToUi = { action -> runOnUiThread(action) },
            onState = { state ->
                if (scanEpoch.get() == epoch &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    when (state) {
                        PhoneNativeProofSession.State.OFFERING ->
                            status.setText(R.string.scan_native_offering)
                        PhoneNativeProofSession.State.WAITING_FOR_TV -> {
                            status.setText(R.string.scan_native_waiting)
                            verifyNativeButton.visibility = View.VISIBLE
                            verifyNativeButton.isEnabled = true
                        }
                        PhoneNativeProofSession.State.VERIFYING ->
                            status.setText(R.string.scan_native_verifying)
                        PhoneNativeProofSession.State.VERIFIED -> {
                            status.setText(R.string.scan_native_verified)
                            verifyNativeButton.visibility = View.GONE
                            mxh1PortInput.visibility = View.VISIBLE
                            mxh1Button.visibility = View.VISIBLE
                            mxh1Button.isEnabled = true
                            mxh1PortInput.isEnabled = true
                        }
                        PhoneNativeProofSession.State.MXH1_VERIFYING ->
                            status.setText(R.string.scan_mxh1_verifying)
                        PhoneNativeProofSession.State.MXH1_VERIFIED -> {
                            status.setText(R.string.scan_mxh1_verified)
                            mxh1Button.visibility = View.GONE
                            mxh1PortInput.visibility = View.GONE
                        }
                        PhoneNativeProofSession.State.FAILED,
                        PhoneNativeProofSession.State.REVOKED -> {
                            status.setText(if (state == PhoneNativeProofSession.State.REVOKED)
                                R.string.scan_native_revoked else R.string.scan_native_failed)
                            verifyNativeButton.visibility = View.GONE
                            mxh1Button.visibility = View.GONE
                            mxh1PortInput.visibility = View.GONE
                        }
                    }
                }
            },
        )
        nativeSession = session
        startNativeButton.isEnabled = false
        hostInput.isEnabled = false
        portInput.isEnabled = false
        if (!session.begin(endpoint)) {
            status.setText(R.string.scan_native_failed)
        }
    }

    private fun resetScannedSession() {
        // Invalidate callbacks BEFORE closing a blocked connect/read.
        scanEpoch.incrementAndGet()
        pendingNative = null
        nativeSession?.close()
        nativeSession = null
        phoneSigningIdentity?.close()
        phoneSigningIdentity = null
        scanned = null
        delivered.set(false)
        invalidNoticeSent = false
        details.visibility = View.GONE
        nativePanel.visibility = View.GONE
        hostInput.text?.clear()
        portInput.text?.clear()
        verifyNativeButton.visibility = View.GONE
        mxh1Button.visibility = View.GONE
        mxh1PortInput.visibility = View.GONE
        mxh1PortInput.text?.clear()
    }

    override fun onStop() {
        cameraProvider?.unbindAll()
        // The active TCP Socket is synchronously closed; stale worker or
        // permission callbacks cannot revive this ephemeral attempt.
        if (::nativePanel.isInitialized) resetScannedSession()
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdownNow()
        nativeSession?.close()
        nativeSession = null
        phoneSigningIdentity?.close()
        phoneSigningIdentity = null
        scanned = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

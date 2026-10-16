package app.muxtv.phone

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.muxtv.localcontrol.qrcontract.TvPublicQr
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native, signed-app-capable phone entry point for the future LC04 protected channel.
 *
 * The only current operation is local, offline, public-key QR inspection. The Activity
 * has no INTERNET/CAMERA/storage permission; source credentials are never accepted.
 * Importing a QR cannot authorize a TV or pair the phone.
 */
class PhoneQrPreviewActivity : ComponentActivity() {
    private lateinit var result: TextView
    private lateinit var chooser: Button

    private val choosePicture = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val margin = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun heading(text: String, sp: Float): TextView = TextView(this).apply {
            this.text = text
            textSize = sp
            setPadding(0, (6 * density).toInt(), 0, (16 * density).toInt())
        }
        root.addView(heading(getString(R.string.heading), 23f))
        root.addView(heading(getString(R.string.description), 16f))
        chooser = Button(this).apply {
            text = getString(R.string.choose_image)
            setOnClickListener { choosePicture.launch(arrayOf("image/*")) }
        }
        root.addView(chooser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        result = heading(getString(R.string.idle), 17f)
        result.setTextIsSelectable(true)
        root.addView(result)
        root.addView(heading(getString(R.string.warning), 14f))

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun inspectImage(uri: Uri) {
        chooser.isEnabled = false
        result.text = getString(R.string.loading)
        lifecycleScope.launch {
            val fingerprint = try {
                withContext(Dispatchers.Default) {
                    val publicQr = readQrFromImage(uri)
                    TvPublicQr.inspect(publicQr).fingerprintSha256
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null // Never render untrusted file names, QR contents or exception messages.
            }
            chooser.isEnabled = true
            result.text = if (fingerprint == null) {
                getString(R.string.invalid)
            } else {
                getString(R.string.verified) + "\n\n" +
                    fingerprint.chunked(8).joinToString(" ") + "\n\n" +
                    getString(R.string.warning)
            }
        }
    }

    private fun readQrFromImage(uri: Uri): String {
        // Read bounded bytes. ContentProvider lengths are often unknown or attacker-controlled.
        val encoded = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                require(encoded.size() + n <= 8 * 1024 * 1024)
                encoded.write(buf, 0, n)
            }
        } ?: throw IllegalArgumentException("Missing image")
        val bytes = encoded.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..12_000 && bounds.outHeight in 1..12_000)
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDimension / sample > 2_048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("Cannot decode QR image")
        try {
            val w = bitmap.width
            val h = bitmap.height
            require(w in 1..2_048 && h in 1..2_048)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
            return MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(source)), hints,
            ).text
        } finally {
            bitmap.recycle()
        }
    }
}

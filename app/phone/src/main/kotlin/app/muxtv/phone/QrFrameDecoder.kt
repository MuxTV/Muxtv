package app.muxtv.phone

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a single camera Y (luminance) frame with exact dimensions and rotation.
 * This function never logs frame data, sends it to a network, or persists it.
 * The caller owns the CameraX ImageProxy and always closes it in finally.
 */
internal object QrFrameDecoder {
    private const val MAX_PIXELS = 4_194_304

    fun decode(luma: ByteArray, width: Int, height: Int, rotation: Int): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS ||
            luma.size != width * height || rotation !in listOf(0, 90, 180, 270)
        ) return null
        val (data, rw, rh) = when (rotation) {
            90 -> Triple(rotate90(luma, width, height), height, width)
            180 -> Triple(rotate180(luma, width, height), width, height)
            270 -> Triple(rotate270(luma, width, height), height, width)
            else -> Triple(luma, width, height)
        }
        val image = PlanarYUVLuminanceSource(data, rw, rh, 0, 0, rw, rh, false)
        val qr = QRCodeReader()
        return try {
            qr.decode(BinaryBitmap(HybridBinarizer(image))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            qr.reset()
        }
    }

    private fun rotate90(data: ByteArray, w: Int, h: Int) = ByteArray(data.size).also { result ->
        for (y in 0 until h) for (x in 0 until w)
            result[x * h + (h - 1 - y)] = data[y * w + x]
    }

    private fun rotate180(data: ByteArray, w: Int, h: Int) = ByteArray(data.size).also { result ->
        for (y in 0 until h) for (x in 0 until w)
            result[(h - 1 - y) * w + (w - 1 - x)] = data[y * w + x]
    }

    private fun rotate270(data: ByteArray, w: Int, h: Int) = ByteArray(data.size).also { result ->
        for (y in 0 until h) for (x in 0 until w)
            result[(w - 1 - x) * h + y] = data[y * w + x]
    }
}

package app.muxtv.phone

import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * CameraX YUV_420_888 luminance-plane decoder. Row/pixel stride and image rotation
 * are normalized before ZXing; no cloud decoder or external app receives camera frames.
 * A QR string is returned in volatile memory only; caller must validate it strictly.
 */
internal object QrLumaDecoder {
    fun decode(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        degrees: Int,
    ): String? {
        if (width !in 1..1920 || height !in 1..1080 ||
            rowStride < width || pixelStride !in 1..4 ||
            degrees !in listOf(0, 90, 180, 270)) return null
        if (data.size < (height - 1L) * rowStride + (width - 1L) * pixelStride + 1L) return null
        var plane = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                plane[row * width + column] =
                    data[row * rowStride + column * pixelStride]
            }
        }
        val outWidth = if (degrees == 90 || degrees == 270) height else width
        val outHeight = if (degrees == 90 || degrees == 270) width else height
        if (degrees != 0) {
            val rotated = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val (rx, ry) = when (degrees) {
                    90 -> (height - 1 - y) to x
                    180 -> (width - 1 - x) to (height - 1 - y)
                    else -> y to (width - 1 - x)
                }
                rotated[ry * outWidth + rx] = plane[y * width + x]
            }
            plane = rotated
        }
        return try {
            val lum = PlanarYUVLuminanceSource(
                plane, outWidth, outHeight, 0, 0, outWidth, outHeight, false,
            )
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(lum))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: com.google.zxing.ReaderException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            plane.fill(0)
        }
    }
}

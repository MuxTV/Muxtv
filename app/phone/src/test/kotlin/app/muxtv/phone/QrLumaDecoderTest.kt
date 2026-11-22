package app.muxtv.phone

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test

class QrLumaDecoderTest {
    private fun qrText(): String =
        "MXLC04:1:" + "a".repeat(32) + ":" + "b".repeat(32) + ":PUBLIC_ONLY_TEST"

    private fun image(): Pair<Int, ByteArray> {
        val size = 296
        val matrix = QRCodeWriter().encode(qrText(), BarcodeFormat.QR_CODE, size, size)
        val pixels = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0 else 255.toByte()
        }
        return size to pixels
    }

    @Test fun decodesLocalQrFromPlainGrayscale() {
        val (width, pixels) = image()
        assertThat(QrLumaDecoder.decode(
            pixels, width, width, width, 1, 0,
        )).isEqualTo(qrText())
    }

    @Test fun acceptsRowPaddingAndYPlanePixelStrideWithoutMixingChroma() {
        val (width, pixels) = image()
        val stride = width * 2 + 12
        val strided = ByteArray(stride * width) { 17 }
        for (y in 0 until width) for (x in 0 until width) {
            strided[y * stride + 2 * x] = pixels[y * width + x]
        }
        assertThat(QrLumaDecoder.decode(
            strided, width, width, stride, 2, 0,
        )).isEqualTo(qrText())
    }

    @Test fun handlesRightAngleAndUpsideDownCameraRotation() {
        val (width, pixels) = image()
        for (degrees in listOf(90, 180, 270)) {
            assertThat(QrLumaDecoder.decode(
                pixels, width, width, width, 1, degrees,
            )).isEqualTo(qrText())
        }
    }

    @Test fun rejectsMalformedFrameWithoutOutOfBoundsOrDecode() {
        assertThat(QrLumaDecoder.decode(ByteArray(5), 200, 200, 200, 1, 0)).isNull()
        assertThat(QrLumaDecoder.decode(ByteArray(50), 10, 10, 4, 1, 0)).isNull()
        assertThat(QrLumaDecoder.decode(ByteArray(100), 10, 10, 10, 1, 15)).isNull()
        assertThat(QrLumaDecoder.decode(ByteArray(100), 10, 10, 10, 0, 0)).isNull()
        assertThat(QrLumaDecoder.decode(ByteArray(5), 4000, 4000, 4000, 1, 0)).isNull()
    }
}

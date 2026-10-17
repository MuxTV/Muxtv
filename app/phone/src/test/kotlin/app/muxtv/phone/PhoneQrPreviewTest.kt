package app.muxtv.phone

import app.muxtv.localcontrol.cryptolab.ExperimentalHpkeLab
import app.muxtv.localcontrol.cryptolab.LabBinding
import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Test

class PhoneQrPreviewTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))

    @Test fun realPublicTvKeyCanBeDecodedFromCameraLuminanceQr() {
        val keyset = ExperimentalHpkeLab.newReceiver(binding)
        val qr = LabTvQrKeyBinding.encode(binding, keyset.publicKeyset)
        val frame = qrLuminance(qr, 520)
        assertThat(QrFrameDecoder.decode(frame, 520, 520, 0)).isEqualTo(qr)
        val result = TvQrVerification.verify(qr)
        assertThat(result).isInstanceOf(TvQrVerification.Result.PublicKeyPreview::class.java)
        val preview = result as TvQrVerification.Result.PublicKeyPreview
        assertThat(preview.fingerprint).matches("[0-9a-f]{64}")
        assertThat(preview.invitationId).isEqualTo(binding.tvInvitationId)
        assertThat(TvQrVerification.formattedFingerprint(preview.fingerprint).lines()).hasSize(8)
    }

    @Test fun rotatingCameraFramesDoesNotChangeQrText() {
        val qr = "MXLC04:1:demo-nonsecret-camera-test"
        val pixels = qrLuminance(qr, 420)
        assertThat(QrFrameDecoder.decode(pixels, 420, 420, 0)).isEqualTo(qr)
        val ninety = rotateClockwise(pixels, 420, 420)
        val upright = rotateClockwise(rotateClockwise(
            rotateClockwise(ninety, 420, 420), 420, 420), 420, 420)
        assertThat(QrFrameDecoder.decode(ninety, 420, 420, 270)).isEqualTo(qr)
        assertThat(QrFrameDecoder.decode(upright, 420, 420, 0)).isEqualTo(qr)
    }

    @Test fun httpPairingQrArbitraryAndOversizedInputAreRejected() {
        listOf(
            "http://192.168.0.20:1234/pair#proof",
            "MXLC04:1:",
            "MXLC04:1:" + "x".repeat(901),
            "not a QR",
            "",
        ).forEach {
            assertThat(TvQrVerification.verify(it)).isEqualTo(TvQrVerification.Result.Rejected)
        }
    }

    @Test fun malformedImagesAreNotAcceptedAsKeyData() {
        assertThat(QrFrameDecoder.decode(ByteArray(10), 12, 12, 0)).isNull()
        assertThat(QrFrameDecoder.decode(ByteArray(12), 3, 4, 45)).isNull()
        assertThat(QrFrameDecoder.decode(ByteArray(0), 0, 0, 0)).isNull()
        assertThat(QrFrameDecoder.decode(ByteArray(16), 4, 4, 0)).isNull()
    }

    @Test fun scanningPublicKeyFingerprintContainsNoSecretMaterial() {
        val receiver = ExperimentalHpkeLab.newReceiver(binding)
        val qr = LabTvQrKeyBinding.encode(binding, receiver.publicKeyset)
        assertThat(qr).doesNotContain("private")
        assertThat(qr).doesNotContain("password")
        val result = TvQrVerification.verify(qr) as TvQrVerification.Result.PublicKeyPreview
        assertThat(result.toString()).doesNotContain("password")
    }

    private fun qrLuminance(text: String, size: Int): ByteArray {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { i ->
            if (matrix[i % size, i / size]) 0 else 0xff.toByte()
        }
    }

    private fun rotateClockwise(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(data.size)
        for (y in 0 until height) for (x in 0 until width)
            out[x * height + height - 1 - y] = data[y * width + x]
        return out
    }
}

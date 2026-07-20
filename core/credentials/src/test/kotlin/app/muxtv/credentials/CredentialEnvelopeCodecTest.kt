package app.muxtv.credentials

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialEnvelopeCodecTest {
    @Test
    fun `version one envelope round trips and owns defensive copies`() {
        val iv = ByteArray(12) { index -> index.toByte() }
        val ciphertext = ByteArray(32) { index -> (index + 20).toByte() }

        val encoded = CredentialEnvelopeCodec.encode(iv, ciphertext)
        iv.fill(99)
        ciphertext.fill(88)
        val decoded = CredentialEnvelopeCodec.decode(encoded)

        assertThat(encoded.copyOfRange(0, 4)).isEqualTo(
            byteArrayOf(
                'M'.code.toByte(),
                'X'.code.toByte(),
                'C'.code.toByte(),
                'R'.code.toByte(),
            ),
        )
        assertThat(encoded[4]).isEqualTo(1.toByte())
        assertThat(encoded[5]).isEqualTo(12.toByte())
        assertThat(ByteBuffer.wrap(encoded, 18, 4).int).isEqualTo(32)
        assertThat(decoded.iv()).isEqualTo(ByteArray(12) { index -> index.toByte() })
        assertThat(decoded.ciphertext()).isEqualTo(ByteArray(32) { index -> (index + 20).toByte() })
        assertThat(decoded.toString()).isEqualTo("<credential-envelope:v1>")

        val leakedIv = decoded.iv()
        leakedIv.fill(77)
        assertThat(decoded.iv()).isEqualTo(ByteArray(12) { index -> index.toByte() })
    }

    @Test
    fun `codec rejects invalid input lengths before encoding`() {
        assertFormatFailure(CredentialEnvelopeFormatReason.InvalidIvLength) {
            CredentialEnvelopeCodec.encode(ByteArray(11), ByteArray(16))
        }
        assertFormatFailure(CredentialEnvelopeFormatReason.InvalidCiphertextLength) {
            CredentialEnvelopeCodec.encode(ByteArray(12), ByteArray(15))
        }
        assertFormatFailure(CredentialEnvelopeFormatReason.InvalidCiphertextLength) {
            CredentialEnvelopeCodec.encode(
                ByteArray(12),
                ByteArray(CredentialEnvelopeCodec.MAX_CIPHERTEXT_BYTES + 1),
            )
        }
    }

    @Test
    fun `decoder rejects wrong magic and unsupported version`() {
        val encoded = validEnvelope()
        encoded[0] = 'B'.code.toByte()
        assertDecodeFailure(encoded, CredentialEnvelopeFormatReason.InvalidMagic)

        val unsupported = validEnvelope()
        unsupported[4] = 2.toByte()
        assertDecodeFailure(unsupported, CredentialEnvelopeFormatReason.UnsupportedVersion)
    }

    @Test
    fun `decoder rejects invalid iv and ciphertext lengths`() {
        val badIv = validEnvelope()
        badIv[5] = 11.toByte()
        assertDecodeFailure(badIv, CredentialEnvelopeFormatReason.InvalidIvLength)

        val tooShort = validEnvelope()
        ByteBuffer.wrap(tooShort).putInt(18, 15)
        assertDecodeFailure(tooShort, CredentialEnvelopeFormatReason.InvalidCiphertextLength)

        val tooLarge = validEnvelope()
        ByteBuffer.wrap(tooLarge).putInt(18, CredentialEnvelopeCodec.MAX_CIPHERTEXT_BYTES + 1)
        assertDecodeFailure(tooLarge, CredentialEnvelopeFormatReason.InvalidCiphertextLength)
    }

    @Test
    fun `decoder rejects truncated and trailing data`() {
        val encoded = validEnvelope()
        assertDecodeFailure(encoded.copyOf(encoded.size - 1), CredentialEnvelopeFormatReason.Truncated)
        assertDecodeFailure(encoded + 0x7f.toByte(), CredentialEnvelopeFormatReason.TrailingData)
        assertDecodeFailure(ByteArray(5), CredentialEnvelopeFormatReason.Truncated)
    }

    private fun validEnvelope(): ByteArray = CredentialEnvelopeCodec.encode(
        iv = ByteArray(12) { it.toByte() },
        ciphertext = ByteArray(32) { (it + 10).toByte() },
    )

    private fun assertDecodeFailure(
        encoded: ByteArray,
        expected: CredentialEnvelopeFormatReason,
    ) {
        assertFormatFailure(expected) {
            CredentialEnvelopeCodec.decode(encoded)
        }
    }

    private fun assertFormatFailure(
        expected: CredentialEnvelopeFormatReason,
        block: () -> Unit,
    ) {
        val error = assertThrows(CredentialEnvelopeFormatException::class.java, block)
        assertThat(error.reason).isEqualTo(expected)
        assertThat(error.message).doesNotContain("[")
    }
}

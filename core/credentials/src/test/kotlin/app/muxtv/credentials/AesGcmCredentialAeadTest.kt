package app.muxtv.credentials

import com.google.common.truth.Truth.assertThat
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertThrows
import org.junit.Test

class AesGcmCredentialAeadTest {
    private val firstId = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")
    private val secondId = CredentialId.parse("123e4567-e89b-12d3-a456-426614174001")

    @Test
    fun `aes gcm round trip returns a defensive plaintext copy`() {
        val aead = aead()
        val plaintext = byteArrayOf(1, 2, 3, 4, 5)

        val envelope = aead.encrypt(firstId, plaintext)
        plaintext.fill(9)
        val decrypted = aead.decrypt(firstId, envelope)

        assertThat(envelope.iv().size).isEqualTo(12)
        assertThat(envelope.ciphertext().size).isEqualTo(5 + AesGcmCredentialAead.TAG_BYTES)
        assertThat(decrypted).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(envelope.toString()).isEqualTo("<credential-envelope:v1>")

        decrypted.fill(8)
        assertThat(aead.decrypt(firstId, envelope)).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
    }

    @Test
    fun `encrypting identical plaintext twice uses distinct provider generated ivs`() {
        val aead = aead()
        val plaintext = byteArrayOf(10, 20, 30)

        val first = aead.encrypt(firstId, plaintext)
        val second = aead.encrypt(firstId, plaintext)

        assertThat(first.iv()).isNotEqualTo(second.iv())
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext())
    }

    @Test
    fun `credential id is authenticated as associated data`() {
        val envelope = aead().encrypt(firstId, byteArrayOf(1, 2, 3))

        val error = assertThrows(CredentialAuthenticationException::class.java) {
            aead().decrypt(secondId, envelope)
        }

        assertThat(error.message).isEqualTo("Credential authentication failed.")
        assertThat(error.toString()).doesNotContain(firstId.value)
        assertThat(error.toString()).doesNotContain(secondId.value)
    }

    @Test
    fun `tampered ciphertext fails authentication without exposing data`() {
        val original = aead().encrypt(firstId, byteArrayOf(4, 5, 6))
        val encoded = CredentialEnvelopeCodec.encode(original.iv(), original.ciphertext())
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()
        val tampered = CredentialEnvelopeCodec.decode(encoded)

        val error = assertThrows(CredentialAuthenticationException::class.java) {
            aead().decrypt(firstId, tampered)
        }

        assertThat(error.message).isEqualTo("Credential authentication failed.")
        assertThat(error.toString()).doesNotContain("4")
        assertThat(error.toString()).doesNotContain("5")
        assertThat(error.toString()).doesNotContain("6")
    }

    @Test
    fun `plaintext is bounded so ciphertext always fits envelope`() {
        val aead = aead()

        val accepted = ByteArray(AesGcmCredentialAead.MAX_PLAINTEXT_BYTES)
        assertThat(aead.encrypt(firstId, accepted).ciphertext().size)
            .isEqualTo(CredentialEnvelopeCodec.MAX_CIPHERTEXT_BYTES)

        val error = assertThrows(CredentialPlaintextTooLargeException::class.java) {
            aead.encrypt(firstId, ByteArray(AesGcmCredentialAead.MAX_PLAINTEXT_BYTES + 1))
        }
        assertThat(error.limitBytes).isEqualTo(AesGcmCredentialAead.MAX_PLAINTEXT_BYTES)
        assertThat(error.message).isEqualTo("Credential plaintext exceeds the allowed size.")
    }

    private fun aead(): AesGcmCredentialAead = AesGcmCredentialAead(
        encryptionKey = { FIXED_KEY },
        decryptionKey = { FIXED_KEY },
    )

    private companion object {
        val FIXED_KEY: SecretKey = SecretKeySpec(
            ByteArray(32) { index -> (index + 1).toByte() },
            "AES",
        )
    }
}

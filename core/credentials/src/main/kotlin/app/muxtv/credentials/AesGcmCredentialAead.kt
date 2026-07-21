package app.muxtv.credentials

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialAuthenticationException(
    cause: Throwable,
) : GeneralSecurityException("Credential authentication failed.", cause)

class CredentialCryptographyException(
    cause: Throwable,
) : GeneralSecurityException("Credential cryptography operation failed.", cause)

class CredentialPlaintextTooLargeException(
    val limitBytes: Int,
) : IllegalArgumentException("Credential plaintext exceeds the allowed size.")

class AesGcmCredentialAead(
    private val encryptionKey: () -> SecretKey,
    private val decryptionKey: () -> SecretKey,
) {
    fun encrypt(
        id: CredentialId,
        plaintext: ByteArray,
    ): CredentialEnvelope {
        if (plaintext.size > MAX_PLAINTEXT_BYTES) {
            throw CredentialPlaintextTooLargeException(MAX_PLAINTEXT_BYTES)
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val iv = cipher.iv?.copyOf()
                ?: throw GeneralSecurityException("Cipher did not provide an IV.")
            if (iv.size != IV_BYTES) {
                throw GeneralSecurityException("Cipher provided an unsupported IV length.")
            }
            cipher.updateAAD(associatedData(id))
            val ciphertext = cipher.doFinal(plaintext)
            CredentialEnvelopeCodec.decode(
                CredentialEnvelopeCodec.encode(
                    iv = iv,
                    ciphertext = ciphertext,
                ),
            )
        } catch (error: GeneralSecurityException) {
            throw CredentialCryptographyException(error)
        }
    }

    fun decrypt(
        id: CredentialId,
        envelope: CredentialEnvelope,
    ): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                decryptionKey(),
                GCMParameterSpec(TAG_BITS, envelope.iv()),
            )
            cipher.updateAAD(associatedData(id))
            cipher.doFinal(envelope.ciphertext())
        } catch (error: AEADBadTagException) {
            throw CredentialAuthenticationException(error)
        } catch (error: GeneralSecurityException) {
            throw CredentialCryptographyException(error)
        }
    }

    private fun associatedData(id: CredentialId): ByteArray {
        val idBytes = id.value.toByteArray(StandardCharsets.US_ASCII)
        return ByteBuffer.allocate(AAD_MAGIC.size + 1 + idBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(AAD_MAGIC)
            .put(AAD_VERSION)
            .put(idBytes)
            .array()
    }

    companion object {
        const val TAG_BYTES: Int = 16
        const val MAX_PLAINTEXT_BYTES: Int =
            CredentialEnvelopeCodec.MAX_CIPHERTEXT_BYTES - TAG_BYTES

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = TAG_BYTES * Byte.SIZE_BITS
        private const val AAD_VERSION: Byte = 1

        private val AAD_MAGIC = byteArrayOf(
            'M'.code.toByte(),
            'X'.code.toByte(),
            'A'.code.toByte(),
            'D'.code.toByte(),
        )
    }
}

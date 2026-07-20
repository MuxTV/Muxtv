package app.muxtv.credentials

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CredentialEnvelopeFormatReason {
    InvalidMagic,
    UnsupportedVersion,
    InvalidIvLength,
    InvalidCiphertextLength,
    Truncated,
    TrailingData,
}

class CredentialEnvelopeFormatException(
    val reason: CredentialEnvelopeFormatReason,
) : IllegalArgumentException("Invalid credential envelope: $reason")

class CredentialEnvelope internal constructor(
    private val ivBytes: ByteArray,
    private val ciphertextBytes: ByteArray,
) {
    fun iv(): ByteArray = ivBytes.copyOf()

    fun ciphertext(): ByteArray = ciphertextBytes.copyOf()

    override fun toString(): String = "<credential-envelope:v1>"
}

object CredentialEnvelopeCodec {
    const val MAX_CIPHERTEXT_BYTES: Int = 64 * 1024

    private const val VERSION: Byte = 1
    private const val IV_LENGTH: Int = 12
    private const val MIN_CIPHERTEXT_BYTES: Int = 16
    private const val HEADER_BYTES: Int = 4 + 1 + 1 + IV_LENGTH + Int.SIZE_BYTES

    private val magic = byteArrayOf(
        'M'.code.toByte(),
        'X'.code.toByte(),
        'C'.code.toByte(),
        'R'.code.toByte(),
    )

    fun encode(
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        validateIvLength(iv.size)
        validateCiphertextLength(ciphertext.size)

        return ByteBuffer.allocate(HEADER_BYTES + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION)
            .put(IV_LENGTH.toByte())
            .put(iv)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    fun decode(encoded: ByteArray): CredentialEnvelope {
        if (encoded.size < HEADER_BYTES) {
            throw formatFailure(CredentialEnvelopeFormatReason.Truncated)
        }

        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size)
        buffer.get(actualMagic)
        if (!actualMagic.contentEquals(magic)) {
            throw formatFailure(CredentialEnvelopeFormatReason.InvalidMagic)
        }

        if (buffer.get() != VERSION) {
            throw formatFailure(CredentialEnvelopeFormatReason.UnsupportedVersion)
        }

        val ivLength = buffer.get().toInt() and 0xff
        validateIvLength(ivLength)

        val iv = ByteArray(IV_LENGTH)
        buffer.get(iv)

        val ciphertextLength = buffer.int
        validateCiphertextLength(ciphertextLength)

        when {
            buffer.remaining() < ciphertextLength ->
                throw formatFailure(CredentialEnvelopeFormatReason.Truncated)

            buffer.remaining() > ciphertextLength ->
                throw formatFailure(CredentialEnvelopeFormatReason.TrailingData)
        }

        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)
        return CredentialEnvelope(
            ivBytes = iv,
            ciphertextBytes = ciphertext,
        )
    }

    private fun validateIvLength(length: Int) {
        if (length != IV_LENGTH) {
            throw formatFailure(CredentialEnvelopeFormatReason.InvalidIvLength)
        }
    }

    private fun validateCiphertextLength(length: Int) {
        if (length !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            throw formatFailure(CredentialEnvelopeFormatReason.InvalidCiphertextLength)
        }
    }

    private fun formatFailure(
        reason: CredentialEnvelopeFormatReason,
    ): CredentialEnvelopeFormatException = CredentialEnvelopeFormatException(reason)
}

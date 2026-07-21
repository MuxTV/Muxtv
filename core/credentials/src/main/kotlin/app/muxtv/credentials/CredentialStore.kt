package app.muxtv.credentials

import java.io.IOException
import kotlinx.coroutines.CancellationException

interface CredentialStore {
    suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult

    suspend fun read(id: CredentialId): CredentialReadResult

    suspend fun remove(id: CredentialId): CredentialRemoveResult

    suspend fun reset(): CredentialResetResult
}

sealed interface CredentialWriteResult {
    data object Stored : CredentialWriteResult

    data class RejectedTooLarge(
        val limitBytes: Int,
    ) : CredentialWriteResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : CredentialWriteResult
}

sealed interface CredentialReadResult {
    data class Found(
        val secret: SecretBytes,
    ) : CredentialReadResult

    data object NotFound : CredentialReadResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : CredentialReadResult
}

sealed interface CredentialRemoveResult {
    data object Removed : CredentialRemoveResult

    data object NotFound : CredentialRemoveResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : CredentialRemoveResult
}

sealed interface CredentialResetResult {
    data object Reset : CredentialResetResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : CredentialResetResult
}

enum class CredentialUnavailableReason {
    KeyMissingOrInvalidated,
    AuthenticationFailed,
    StoreCorrupted,
    IoFailure,
}

interface CredentialRecordStorage {
    suspend fun read(id: CredentialId): ByteArray?

    suspend fun write(
        id: CredentialId,
        encodedEnvelope: ByteArray,
    )

    suspend fun remove(id: CredentialId): Boolean

    suspend fun clear()
}

interface CredentialCipher {
    fun encrypt(
        id: CredentialId,
        plaintext: ByteArray,
    ): ByteArray

    fun decrypt(
        id: CredentialId,
        encodedEnvelope: ByteArray,
    ): ByteArray

    fun reset()
}

class CredentialRecordCorruptionException(
    cause: Throwable,
) : IOException("Credential record storage is corrupted.", cause)

class DefaultCredentialStore(
    private val records: CredentialRecordStorage,
    private val cipher: CredentialCipher,
) : CredentialStore {
    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult {
        return try {
            val encodedEnvelope = secret.useBytes { plaintext ->
                cipher.encrypt(id, plaintext)
            }
            try {
                records.write(id, encodedEnvelope)
            } finally {
                encodedEnvelope.fill(0)
            }
            CredentialWriteResult.Stored
        } catch (error: CredentialPlaintextTooLargeException) {
            CredentialWriteResult.RejectedTooLarge(error.limitBytes)
        } catch (error: Exception) {
            CredentialWriteResult.Unavailable(error.toUnavailableReason())
        }
    }

    override suspend fun read(id: CredentialId): CredentialReadResult {
        return try {
            val encodedEnvelope = records.read(id) ?: return CredentialReadResult.NotFound
            val plaintext = try {
                cipher.decrypt(id, encodedEnvelope)
            } finally {
                encodedEnvelope.fill(0)
            }

            val secret = try {
                SecretBytes.copyOf(plaintext)
            } finally {
                plaintext.fill(0)
            }
            CredentialReadResult.Found(secret)
        } catch (error: Exception) {
            CredentialReadResult.Unavailable(error.toUnavailableReason())
        }
    }

    override suspend fun remove(id: CredentialId): CredentialRemoveResult {
        return try {
            if (records.remove(id)) {
                CredentialRemoveResult.Removed
            } else {
                CredentialRemoveResult.NotFound
            }
        } catch (error: Exception) {
            CredentialRemoveResult.Unavailable(error.toUnavailableReason())
        }
    }

    override suspend fun reset(): CredentialResetResult {
        return try {
            records.clear()
            cipher.reset()
            CredentialResetResult.Reset
        } catch (error: Exception) {
            CredentialResetResult.Unavailable(error.toUnavailableReason())
        }
    }
}

private fun Exception.toUnavailableReason(): CredentialUnavailableReason {
    if (this is CancellationException) throw this

    return when (this) {
        is CredentialKeyUnavailableException -> CredentialUnavailableReason.KeyMissingOrInvalidated
        is CredentialAuthenticationException -> CredentialUnavailableReason.AuthenticationFailed
        is CredentialEnvelopeFormatException,
        is CredentialRecordCorruptionException,
        -> CredentialUnavailableReason.StoreCorrupted
        else -> CredentialUnavailableReason.IoFailure
    }
}

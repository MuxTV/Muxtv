package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.credentials.CredentialWriteResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RemoteSourceAccessReadResult {
    data class Found(
        val access: RemoteSourceAccess,
    ) : RemoteSourceAccessReadResult

    data object NotFound : RemoteSourceAccessReadResult
    data object Corrupted : RemoteSourceAccessReadResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteSourceAccessReadResult
}

sealed interface RemoteSourceAccessUpdateResult {
    data object Updated : RemoteSourceAccessUpdateResult
    data object Unchanged : RemoteSourceAccessUpdateResult
    data object NotFound : RemoteSourceAccessUpdateResult
    data object Corrupted : RemoteSourceAccessUpdateResult

    data class RejectedTooLarge(
        val limitBytes: Int,
    ) : RemoteSourceAccessUpdateResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteSourceAccessUpdateResult
}

/**
 * The single read/write owner for one encrypted [RemoteSourceAccess] record graph.
 *
 * All onboarding, refresh and playback-approval consumers must share the same singleton instance so
 * read-modify-write mutations cannot overwrite one another with stale encrypted records.
 */
class RemoteSourceAccessManager(
    private val credentialStore: CredentialStore,
) {
    private val mutationMutex = Mutex()

    suspend fun save(
        id: CredentialId,
        access: RemoteSourceAccess,
    ): CredentialWriteResult = mutationMutex.withLock {
        writeUnlocked(id, access)
    }

    suspend fun read(id: CredentialId): RemoteSourceAccessReadResult = mutationMutex.withLock {
        readUnlocked(id)
    }

    suspend fun update(
        id: CredentialId,
        transform: (RemoteSourceAccess) -> RemoteSourceAccess,
    ): RemoteSourceAccessUpdateResult = mutationMutex.withLock {
        when (val current = readUnlocked(id)) {
            is RemoteSourceAccessReadResult.Found -> {
                val updated = transform(current.access)
                if (updated === current.access) {
                    return@withLock RemoteSourceAccessUpdateResult.Unchanged
                }
                when (val stored = writeUnlocked(id, updated)) {
                    CredentialWriteResult.Stored -> RemoteSourceAccessUpdateResult.Updated
                    is CredentialWriteResult.RejectedTooLarge ->
                        RemoteSourceAccessUpdateResult.RejectedTooLarge(stored.limitBytes)

                    is CredentialWriteResult.Unavailable ->
                        RemoteSourceAccessUpdateResult.Unavailable(stored.reason)
                }
            }

            RemoteSourceAccessReadResult.NotFound -> RemoteSourceAccessUpdateResult.NotFound
            RemoteSourceAccessReadResult.Corrupted -> RemoteSourceAccessUpdateResult.Corrupted
            is RemoteSourceAccessReadResult.Unavailable ->
                RemoteSourceAccessUpdateResult.Unavailable(current.reason)
        }
    }

    suspend fun remove(id: CredentialId): CredentialRemoveResult = mutationMutex.withLock {
        credentialStore.remove(id)
    }

    private suspend fun readUnlocked(id: CredentialId): RemoteSourceAccessReadResult =
        when (val credential = credentialStore.read(id)) {
            is CredentialReadResult.Found -> credential.secret.use { secret ->
                try {
                    RemoteSourceAccessReadResult.Found(RemoteSourceAccessCodec.decode(secret))
                } catch (_: RemoteSourceAccessFormatException) {
                    RemoteSourceAccessReadResult.Corrupted
                }
            }

            CredentialReadResult.NotFound -> RemoteSourceAccessReadResult.NotFound
            is CredentialReadResult.Unavailable ->
                RemoteSourceAccessReadResult.Unavailable(credential.reason)
        }

    private suspend fun writeUnlocked(
        id: CredentialId,
        access: RemoteSourceAccess,
    ): CredentialWriteResult = RemoteSourceAccessCodec.encode(access).use { encoded ->
        credentialStore.put(id, encoded)
    }
}

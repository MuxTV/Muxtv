package app.muxtv.catalog.onboarding

import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.refresh.SourceAccessKind
import app.muxtv.catalog.refresh.SourceAccessReference
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.database.PendingSourcePreparation
import app.muxtv.database.PendingSourcePreparationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class DurablePreparationRegistrationResult {
    Registered,
    StorageUnavailable,
}

data class DurablePreparedSource(
    val accessReference: SourceAccessReference,
    val scheme: String,
    val host: String,
) {
    init {
        require(scheme == "http" || scheme == "https")
        require(host.isNotBlank())
    }
}

class DurableRemoteSourceOnboarding(
    private val delegate: RemoteSourceOnboarding,
    private val registry: PendingSourcePreparationStore,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : RemoteSourceOnboarding {
    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult {
        val result = delegate.prepare(input)
        if (result !is RemoteSourcePreparationResult.Prepared) return result

        return when (
            registerPrepared(
                preparationId = result.token.value,
                scheme = result.scheme,
                host = result.host,
                rollbackPrepared = { delegate.cancel(result.token) },
            )
        ) {
            DurablePreparationRegistrationResult.Registered -> result
            DurablePreparationRegistrationResult.StorageUnavailable ->
                RemoteSourcePreparationResult.CredentialUnavailable(
                    CredentialUnavailableReason.IoFailure,
                )
        }
    }

    suspend fun registerPrepared(
        preparationId: String,
        scheme: String,
        host: String,
        rollbackPrepared: suspend () -> Unit,
    ): DurablePreparationRegistrationResult {
        val createdAt = currentTimeMillis()
        val preparation = PendingSourcePreparation(
            preparationId = preparationId,
            scheme = scheme,
            host = host,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = createdAt + PREPARATION_TTL_MILLIS,
        )
        try {
            registry.upsert(preparation)
        } catch (cancelled: CancellationException) {
            rollbackPreparedBestEffort(rollbackPrepared)
            throw cancelled
        } catch (_: Exception) {
            rollbackPreparedBestEffort(rollbackPrepared)
            return DurablePreparationRegistrationResult.StorageUnavailable
        }
        return DurablePreparationRegistrationResult.Registered
    }

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult {
        val result = delegate.activate(token, sourceName)
        val cleanupComplete = when (result) {
            is RemoteSourceActivationResult.Activated -> true
            is RemoteSourceActivationResult.Failed ->
                result.credentialCleanupFailure == null && result.sourceCleanupFailure == null
        }
        if (cleanupComplete) {
            removeRegistryAfterCompletedSideEffect(token)
        }
        return result
    }

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult {
        val result = delegate.cancel(token)
        if (
            result == RemoteSourceCancellationResult.Removed ||
            result == RemoteSourceCancellationResult.NotFound
        ) {
            removeRegistryAfterCompletedSideEffect(token)
        }
        return result
    }

    suspend fun restoreLatestRegistered(): DurablePreparedSource? {
        val now = currentTimeMillis()
        repeat(MAX_RESTORE_ATTEMPTS) {
            val preparation = try {
                registry.getLatestActive(now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            } ?: return null

            val accessReference = try {
                SourceAccessReference.parse(preparation.preparationId)
            } catch (_: IllegalArgumentException) {
                if (!removeRegistryByIdBestEffort(preparation.preparationId)) return null
                return@repeat
            }

            return DurablePreparedSource(
                accessReference = accessReference,
                scheme = preparation.scheme,
                host = preparation.host,
            )
        }
        return null
    }

    suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared? {
        val restored = restoreLatestRegistered() ?: return null
        if (restored.accessReference.kind != SourceAccessKind.M3U) return null
        return RemoteSourcePreparationResult.Prepared(
            token = RemoteSourcePreparationToken.parse(restored.accessReference.credentialId.value),
            scheme = restored.scheme,
            host = restored.host,
        )
    }

    suspend fun cleanupExpired(): PendingPreparationCleanupSummary {
        val now = currentTimeMillis()
        val expired = try {
            registry.getExpired(
                nowEpochMillis = now,
                limit = MAX_STARTUP_CLEANUP_BATCH,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PendingPreparationCleanupSummary(
                inspected = 0,
                removed = 0,
                retained = 0,
                failures = 1,
            )
        }

        var removed = 0
        var retained = 0
        var failures = 0
        for (preparation in expired) {
            val token = try {
                RemoteSourcePreparationToken.parse(preparation.preparationId)
            } catch (_: IllegalArgumentException) {
                if (removeRegistryByIdBestEffort(preparation.preparationId)) {
                    removed += 1
                } else {
                    failures += 1
                }
                continue
            }

            val result = try {
                delegate.cancel(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += 1
                continue
            }

            if (
                result == RemoteSourceCancellationResult.Removed ||
                result == RemoteSourceCancellationResult.NotFound
            ) {
                if (removeRegistryByIdBestEffort(preparation.preparationId)) {
                    removed += 1
                } else {
                    failures += 1
                }
            } else {
                retained += 1
            }
        }

        return PendingPreparationCleanupSummary(
            inspected = expired.size,
            removed = removed,
            retained = retained,
            failures = failures,
        )
    }

    private suspend fun rollbackPreparedBestEffort(rollbackPrepared: suspend () -> Unit) {
        withContext(NonCancellable) {
            try {
                rollbackPrepared()
            } catch (_: Exception) {
                // The pending credential remains recoverable by domain-specific cleanup/reconciliation.
            }
        }
    }

    private suspend fun removeRegistryAfterCompletedSideEffect(token: RemoteSourcePreparationToken) {
        withContext(NonCancellable) {
            try {
                registry.remove(token.value)
            } catch (_: Exception) {
                // Keep the durable row for the next bounded startup cleanup.
            }
        }
    }

    private suspend fun removeRegistryByIdBestEffort(preparationId: String): Boolean = try {
        registry.remove(preparationId)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PREPARATION_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val MAX_STARTUP_CLEANUP_BATCH = 50
        const val MAX_RESTORE_ATTEMPTS = 10
    }
}

data class PendingPreparationCleanupSummary(
    val inspected: Int,
    val removed: Int,
    val retained: Int,
    val failures: Int,
) {
    init {
        require(inspected >= 0)
        require(removed >= 0)
        require(retained >= 0)
        require(failures >= 0)
        require(removed + retained <= inspected)
    }
}

package app.muxtv.catalog.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.muxtv.catalog.refresh.EpgHttpValidators
import app.muxtv.catalog.refresh.RemoteEpgRefreshRequest
import app.muxtv.catalog.refresh.RemoteEpgRefresher
import app.muxtv.credentials.CredentialId
import app.muxtv.database.EpgRefreshStore
import app.muxtv.database.EpgRefreshTarget
import app.muxtv.database.EpgRefreshTrigger
import app.muxtv.database.RefreshCompletionDisposition
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal const val EPG_REFRESH_TIMEOUT_MILLIS = 20 * 60 * 1000L
internal const val EPG_LEASE_STALE_AFTER_MILLIS = 30 * 60 * 1000L

internal suspend fun finalizeCancellationAndRethrow(
    cancellation: CancellationException,
    finalize: suspend () -> Unit,
): Nothing {
    try {
        finalize()
    } catch (_: Exception) {
        // Persistence is best-effort here. The original coroutine cancellation remains authoritative.
    }
    throw cancellation
}

@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val refreshStore: EpgRefreshStore,
    private val epgRefresher: RemoteEpgRefresher,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { value -> runCatching { EpgRefreshTrigger.valueOf(value) }.getOrNull() }
            ?: return Result.failure()

        val targetResult = runWorkerBoundary { refreshStore.getTarget(sourceId) }
        if (targetResult.isFailure) return transientWorkResult()
        val target = targetResult.getOrNull() ?: return Result.failure()

        val runToken = UUID.randomUUID().toString()
        val startedAtEpochMillis = System.currentTimeMillis()
        val acquiredResult = runWorkerBoundary {
            refreshStore.tryAcquire(
                sourceId = sourceId,
                runToken = runToken,
                startedAtEpochMillis = startedAtEpochMillis,
                staleBeforeEpochMillis = startedAtEpochMillis - EPG_LEASE_STALE_AFTER_MILLIS,
            )
        }
        if (acquiredResult.isFailure) return transientWorkResult()
        if (!acquiredResult.getOrThrow()) return Result.success()

        return try {
            val decision = refresh(target, runToken)
            val disposition = complete(target, runToken, trigger, decision)
            decision.toWorkResult(disposition)
        } catch (cancelled: CancellationException) {
            finalizeCancellationAndRethrow(cancelled) {
                complete(
                    target = target,
                    runToken = runToken,
                    trigger = trigger,
                    decision = EpgRefreshOutcomeMapper.cancellation(),
                )
            }
        } catch (_: Exception) {
            val decision = EpgRefreshOutcomeMapper.internalFailure()
            val disposition = runCatching {
                complete(target, runToken, trigger, decision)
            }.getOrNull()
            decision.toWorkResult(disposition)
        }
    }

    private suspend fun refresh(
        target: EpgRefreshTarget,
        runToken: String,
    ): EpgRefreshDecision {
        val accessRef = target.accessRef
            ?: return EpgRefreshOutcomeMapper.missingAccessReference()
        val credentialId = runCatching { CredentialId.parse(accessRef) }
            .getOrElse { return EpgRefreshOutcomeMapper.invalidAccessReference() }
        val request = RemoteEpgRefreshRequest(
            sourceId = target.sourceId,
            sourceName = target.sourceName,
            providerSourceId = target.providerSourceId,
            accessCredentialId = credentialId,
            defaultZoneId = target.defaultZoneId,
            refreshRunToken = runToken,
            validators = EpgHttpValidators(
                etag = target.validators.etag,
                lastModified = target.validators.lastModified,
            ),
        )

        return try {
            withTimeout(EPG_REFRESH_TIMEOUT_MILLIS) {
                EpgRefreshOutcomeMapper.map(epgRefresher.refresh(request))
            }
        } catch (_: TimeoutCancellationException) {
            EpgRefreshOutcomeMapper.runtimeTimeout()
        }
    }

    private suspend fun complete(
        target: EpgRefreshTarget,
        runToken: String,
        trigger: EpgRefreshTrigger,
        decision: EpgRefreshDecision,
    ): RefreshCompletionDisposition = withContext(NonCancellable) {
        refreshStore.completeWithDisposition(
            sourceId = target.sourceId,
            runToken = runToken,
            trigger = trigger,
            completion = decision.toCompletion(
                completedAtEpochMillis = System.currentTimeMillis(),
                accessRefBinding = target.accessRef.orEmpty(),
            ),
            expectedAccessRef = target.accessRef,
        )
    }

    private fun EpgRefreshDecision.toWorkResult(
        disposition: RefreshCompletionDisposition?,
    ): Result = when {
        disposition != null && disposition != RefreshCompletionDisposition.APPLIED -> Result.success()
        workSucceeded -> Result.success()
        retryable -> transientWorkResult()
        else -> Result.failure()
    }

    private fun transientWorkResult(): Result =
        if (runAttemptCount < MAX_RETRY_INDEX) Result.retry() else Result.failure()

    companion object {
        const val KEY_SOURCE_ID = "epg_source_id"
        const val KEY_TRIGGER = "epg_trigger"

        private const val MAX_RETRY_INDEX = 2
    }
}

package app.muxtv.catalog.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.muxtv.catalog.refresh.RemoteSourceRefreshRequest
import app.muxtv.catalog.refresh.RemoteSourceRefresher
import app.muxtv.credentials.CredentialId
import app.muxtv.database.EpgMatchingStore
import app.muxtv.database.RefreshCompletionDisposition
import app.muxtv.database.SourceRefreshCompletion
import app.muxtv.database.SourceRefreshRunState
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRefreshTarget
import app.muxtv.database.SourceRefreshTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal const val REFRESH_TIMEOUT_MILLIS = 20 * 60 * 1000L
internal const val LEASE_STALE_AFTER_MILLIS = 30 * 60 * 1000L

@HiltWorker
class SourceRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val refreshStore: SourceRefreshStore,
    private val sourceRefresher: RemoteSourceRefresher,
    private val matchingStore: EpgMatchingStore,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { value -> runCatching { SourceRefreshTrigger.valueOf(value) }.getOrNull() }
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
                staleBeforeEpochMillis = startedAtEpochMillis - LEASE_STALE_AFTER_MILLIS,
            )
        }
        if (acquiredResult.isFailure) return transientWorkResult()
        if (!acquiredResult.getOrThrow()) return Result.success()

        return try {
            val decision = refresh(target, runToken)
            val disposition = complete(target, runToken, trigger, decision)
            reconcileSourceAfterPublication(decision, disposition) {
                matchingStore.reconcileProviderSource(target.sourceId)
            }
            decision.toWorkResult(disposition)
        } catch (cancelled: CancellationException) {
            finalizeCancellationAndRethrow(cancelled) {
                complete(
                    target = target,
                    runToken = runToken,
                    trigger = trigger,
                    decision = SourceRefreshDecision(
                        state = SourceRefreshRunState.CANCELLED,
                        resultFamily = "WORK",
                        resultCode = "CANCELLED",
                    ),
                )
            }
        } catch (_: Exception) {
            val decision = SourceRefreshOutcomeMapper.internalFailure()
            val disposition = runCatching {
                complete(target, runToken, trigger, decision)
            }.getOrNull()
            decision.toWorkResult(disposition)
        }
    }

    private suspend fun refresh(
        target: SourceRefreshTarget,
        runToken: String,
    ): SourceRefreshDecision {
        val credentialRef = target.credentialRef
            ?: return SourceRefreshOutcomeMapper.missingCredentialReference()
        val credentialId = runCatching { CredentialId.parse(credentialRef) }
            .getOrElse { return SourceRefreshOutcomeMapper.invalidCredentialReference() }
        val request = RemoteSourceRefreshRequest(
            sourceId = target.sourceId,
            sourceName = target.sourceName,
            accessCredentialId = credentialId,
            refreshRunToken = runToken,
        )

        return try {
            withTimeout(REFRESH_TIMEOUT_MILLIS) {
                SourceRefreshOutcomeMapper.map(sourceRefresher.refresh(request))
            }
        } catch (_: TimeoutCancellationException) {
            SourceRefreshOutcomeMapper.runtimeTimeout()
        }
    }

    private suspend fun complete(
        target: SourceRefreshTarget,
        runToken: String,
        trigger: SourceRefreshTrigger,
        decision: SourceRefreshDecision,
    ): RefreshCompletionDisposition = withContext(NonCancellable) {
        refreshStore.completeWithDisposition(
            sourceId = target.sourceId,
            runToken = runToken,
            trigger = trigger,
            completion = SourceRefreshCompletion(
                state = decision.state,
                resultFamily = decision.resultFamily,
                resultCode = decision.resultCode,
                completedAtEpochMillis = System.currentTimeMillis(),
                revisionNumber = decision.revisionNumber,
                parsedEntries = decision.parsedEntries,
                skippedEntries = decision.skippedEntries,
                warningCount = decision.warningCount,
                httpStatus = decision.httpStatus,
            ),
            expectedCredentialRef = target.credentialRef,
        )
    }

    private fun SourceRefreshDecision.toWorkResult(
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
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_TRIGGER = "trigger"

        private const val MAX_RETRY_INDEX = 2
    }
}

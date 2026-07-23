package app.muxtv.catalog.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.muxtv.catalog.refresh.RemoteSourceRefreshRequest
import app.muxtv.catalog.refresh.RemoteSourceRefresher
import app.muxtv.credentials.CredentialId
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
import kotlinx.coroutines.withContext

@HiltWorker
class SourceRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val refreshStore: SourceRefreshStore,
    private val sourceRefresher: RemoteSourceRefresher,
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
            val decision = refresh(target)
            complete(sourceId, runToken, trigger, decision)
            decision.toWorkResult()
        } catch (cancelled: CancellationException) {
            complete(
                sourceId = sourceId,
                runToken = runToken,
                trigger = trigger,
                decision = SourceRefreshDecision(
                    state = SourceRefreshRunState.CANCELLED,
                    resultFamily = "WORK",
                    resultCode = "CANCELLED",
                ),
            )
            throw cancelled
        } catch (_: Exception) {
            val decision = SourceRefreshOutcomeMapper.internalFailure()
            runCatching {
                complete(sourceId, runToken, trigger, decision)
            }
            decision.toWorkResult()
        }
    }

    private suspend fun refresh(target: SourceRefreshTarget): SourceRefreshDecision {
        val credentialRef = target.credentialRef
            ?: return SourceRefreshOutcomeMapper.missingCredentialReference()
        val credentialId = runCatching { CredentialId.parse(credentialRef) }
            .getOrElse { return SourceRefreshOutcomeMapper.invalidCredentialReference() }

        return SourceRefreshOutcomeMapper.map(
            sourceRefresher.refresh(
                RemoteSourceRefreshRequest(
                    sourceId = target.sourceId,
                    sourceName = target.sourceName,
                    accessCredentialId = credentialId,
                ),
            ),
        )
    }

    private suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: SourceRefreshTrigger,
        decision: SourceRefreshDecision,
    ) = withContext(NonCancellable) {
        refreshStore.complete(
            sourceId = sourceId,
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
        )
    }

    private fun SourceRefreshDecision.toWorkResult(): Result = when {
        state == SourceRefreshRunState.SUCCEEDED -> Result.success()
        retryable -> transientWorkResult()
        else -> Result.failure()
    }

    private fun transientWorkResult(): Result =
        if (runAttemptCount < MAX_RETRY_INDEX) Result.retry() else Result.failure()

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_TRIGGER = "trigger"

        private const val LEASE_STALE_AFTER_MILLIS = 30 * 60 * 1000L
        private const val MAX_RETRY_INDEX = 2
    }
}

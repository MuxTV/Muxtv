package app.muxtv.catalog.sync

import app.muxtv.database.EpgRefreshHttpValidators
import app.muxtv.database.RefreshCompletionDisposition
import app.muxtv.database.SourceRefreshRunState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RefreshMatchingPublicationTest {
    @Test
    fun appliedEpgRefreshReconcilesMatching() = runBlocking {
        var calls = 0

        reconcileEpgAfterPublication(
            decision = refreshedEpgDecision(),
            disposition = RefreshCompletionDisposition.APPLIED,
        ) { calls++ }

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun epgNotModifiedChecksMatchingFreshnessWithoutForcingRematch() = runBlocking {
        var calls = 0

        reconcileEpgAfterPublication(
            decision = EpgRefreshDecision.NotModified(EpgRefreshHttpValidators()),
            disposition = RefreshCompletionDisposition.APPLIED,
        ) { calls++ }

        // The worker callback is reconcileIfStale: a 304 performs only the cheap freshness check when
        // current, but still repairs legacy/missing derived state after a policy migration.
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun staleEpgCompletionDoesNotReconcileMatching() = runBlocking {
        var calls = 0

        reconcileEpgAfterPublication(
            decision = refreshedEpgDecision(),
            disposition = RefreshCompletionDisposition.SUPERSEDED,
        ) { calls++ }

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun appliedCatalogRefreshReconcilesLinkedGuideSources() = runBlocking {
        var calls = 0

        reconcileSourceAfterPublication(
            decision = refreshedSourceDecision(),
            disposition = RefreshCompletionDisposition.APPLIED,
        ) { calls++ }

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun failedCatalogRefreshDoesNotReconcileMatching() = runBlocking {
        var calls = 0

        reconcileSourceAfterPublication(
            decision = SourceRefreshDecision(
                state = SourceRefreshRunState.FAILED,
                resultFamily = "NETWORK",
                resultCode = "IO",
                retryable = true,
            ),
            disposition = RefreshCompletionDisposition.APPLIED,
        ) { calls++ }

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun matchingFailureIsBestEffortAfterDurablePublication() = runBlocking {
        val result = runCatching {
            reconcileEpgAfterPublication(
                decision = refreshedEpgDecision(),
                disposition = RefreshCompletionDisposition.APPLIED,
            ) {
                throw IllegalStateException("synthetic matching failure")
            }
        }

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun matchingCancellationRemainsAuthoritative() = runBlocking {
        val original = CancellationException("synthetic matching cancellation")
        var observed: CancellationException? = null

        try {
            reconcileEpgAfterPublication(
                decision = refreshedEpgDecision(),
                disposition = RefreshCompletionDisposition.APPLIED,
            ) {
                throw original
            }
        } catch (cancelled: CancellationException) {
            observed = cancelled
        }

        assertThat(observed).isSameInstanceAs(original)
    }

    private fun refreshedEpgDecision(): EpgRefreshDecision.Refreshed =
        EpgRefreshDecision.Refreshed(
            revisionNumber = 2,
            channelCount = 10,
            programmeCount = 100,
            skippedProgrammeCount = 0,
            warningCount = 0,
            unresolvedTimeCount = 0,
            validators = EpgRefreshHttpValidators(),
        )

    private fun refreshedSourceDecision(): SourceRefreshDecision =
        SourceRefreshDecision(
            state = SourceRefreshRunState.SUCCEEDED,
            resultFamily = "SUCCESS",
            resultCode = null,
            revisionNumber = 2,
            parsedEntries = 10,
            workSucceeded = true,
        )
}

package app.muxtv.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.database.measurement.C03ProductionRoomCorrectnessRunner
import app.muxtv.database.measurement.C03ProductionScenario
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C03ProductionRoomAbCorrectnessTest {
    private val runner = C03ProductionRoomCorrectnessRunner(
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun regularScenariosPreserveNormalizedActiveStateAndLogicalIdentity() = runTest {
        C03ProductionScenario.entries.forEach { scenario ->
            val result = runner.runContentScenario(
                scenario = scenario,
                entryCount = ENTRY_COUNT,
            )

            assertThat(result.production.activeCount).isEqualTo(result.candidate.activeCount)
            assertThat(result.production.activeDigestSha256)
                .isEqualTo(result.candidate.activeDigestSha256)
            assertThat(result.production.logicalIdentityDigestSha256)
                .isEqualTo(result.candidate.logicalIdentityDigestSha256)
            assertThat(result.production.previousGoodDigestSha256)
                .isEqualTo(result.candidate.previousGoodDigestSha256)
            assertThat(result.candidate.activeOrderDigestSha256)
                .isEqualTo(result.expectedIncomingOrderDigestSha256)
        }
    }

    @Test
    fun reorderRewritesMembershipOrderWithoutCreatingCatalogPayloads() = runTest {
        val result = runner.runContentScenario(
            scenario = C03ProductionScenario.REORDER,
            entryCount = ENTRY_COUNT,
        )

        assertThat(result.candidate.catalogPayloadRowsAdded).isEqualTo(0)
        assertThat(result.candidate.searchPayloadRowsAdded).isEqualTo(0)
        assertThat(result.candidate.membershipRowsAdded).isEqualTo(ENTRY_COUNT)
        assertThat(result.candidate.activeOrderDigestSha256)
            .isEqualTo(result.expectedIncomingOrderDigestSha256)
    }

    @Test
    fun tokenChurnPreservesLogicalIdentityButCreatesNewCatalogPayload() = runTest {
        val result = runner.runContentScenario(
            scenario = C03ProductionScenario.TOKEN_CHURN,
            entryCount = ENTRY_COUNT,
        )

        assertThat(result.production.logicalIdentityDigestSha256)
            .isEqualTo(result.candidate.logicalIdentityDigestSha256)
        assertThat(result.candidate.logicalIdentityDigestSha256)
            .isEqualTo(result.baselineLogicalIdentityDigestSha256)
        assertThat(result.candidate.activeDigestSha256)
            .isNotEqualTo(result.baselineActiveDigestSha256)
        assertThat(result.candidate.catalogPayloadRowsAdded).isEqualTo(ENTRY_COUNT)
        assertThat(result.candidate.searchPayloadRowsAdded).isEqualTo(0)
    }

    @Test
    fun duplicateOccurrencesPreserveMultiplicityThroughMembership() = runTest {
        val result = runner.runDuplicateScenario()

        assertThat(result.production.activeCount).isEqualTo(2)
        assertThat(result.candidate.activeCount).isEqualTo(2)
        assertThat(result.candidate.distinctLogicalIdentityCount).isEqualTo(1)
        assertThat(result.candidate.distinctCatalogPayloadCount).isEqualTo(1)
        assertThat(result.candidate.membershipCount).isEqualTo(2)
        assertThat(result.production.activeDigestSha256)
            .isEqualTo(result.candidate.activeDigestSha256)
    }

    @Test
    fun duplicateBrowsePreservesVariantMultiplicityWhenPayloadIsReused() = runTest {
        val result = runner.runDuplicateBrowseScenario()

        assertThat(result.productionVariantCount).isEqualTo(2)
        assertThat(result.candidateVariantCount).isEqualTo(2)
    }

    @Test
    fun partialFailureCannotPublishAndLeavesPreviousGoodActive() = runTest {
        val result = runner.runPartialFailureScenario(entryCount = ENTRY_COUNT)

        assertThat(result.production.activeDigestSha256)
            .isEqualTo(result.production.previousGoodDigestSha256)
        assertThat(result.candidate.activeDigestSha256)
            .isEqualTo(result.candidate.previousGoodDigestSha256)
        assertThat(result.production.stagingEntryCount).isEqualTo(0)
        assertThat(result.candidate.stagingMembershipCount).isEqualTo(0)
        assertThat(result.candidate.orphanRowsAfterBoundedCleanup).isEqualTo(0)
    }

    @Test
    fun staleRefreshGenerationIsRejectedByBothVariants() = runTest {
        val result = runner.runStaleOwnerScenario(entryCount = ENTRY_COUNT)

        assertThat(result.productionSuperseded).isTrue()
        assertThat(result.candidateSuperseded).isTrue()
        assertThat(result.production.activeDigestSha256)
            .isEqualTo(result.production.previousGoodDigestSha256)
        assertThat(result.candidate.activeDigestSha256)
            .isEqualTo(result.candidate.previousGoodDigestSha256)
        assertThat(result.production.stagingEntryCount).isEqualTo(0)
        assertThat(result.candidate.stagingMembershipCount).isEqualTo(0)
    }

    @Test
    fun cancellationDiscardIsBoundedAndCannotPublishLater() = runTest {
        val result = runner.runCancellationScenario(entryCount = ENTRY_COUNT)

        assertThat(result.production.activeDigestSha256)
            .isEqualTo(result.production.previousGoodDigestSha256)
        assertThat(result.candidate.activeDigestSha256)
            .isEqualTo(result.candidate.previousGoodDigestSha256)
        assertThat(result.production.stagingEntryCount).isEqualTo(0)
        assertThat(result.candidate.stagingMembershipCount).isEqualTo(0)
        assertThat(result.candidate.lateActivationPublished).isFalse()
        assertThat(result.candidate.cleanupPasses).isAtMost(MAX_BOUNDED_CLEANUP_PASSES)
        assertThat(result.candidate.orphanRowsAfterBoundedCleanup).isEqualTo(0)
    }


    @Test
    fun stagedSearchRemainsInvisibleUntilGuardedPublication() = runTest {
        val result = runner.runActiveSearchPublicationScenario(
            entryCount = ENTRY_COUNT,
            markerIndex = SEARCH_MARKER_INDEX,
        )

        assertThat(result.productionBefore).isEmpty()
        assertThat(result.candidateBefore).isEmpty()
        assertThat(result.productionDuringStaging).isEmpty()
        assertThat(result.candidateDuringStaging).isEmpty()
        assertThat(result.productionAfter)
            .containsExactly(result.expectedCanonicalChannelId)
        assertThat(result.candidateAfter)
            .containsExactly(result.expectedCanonicalChannelId)
    }

    @Test
    fun repeatedTokenChurnStorageIsBoundedAfterCompaction() = runTest {
        val result = runner.runRepeatedRevisionStorageScenario(
            entryCount = ENTRY_COUNT,
            revisionCount = REPEATED_REVISION_COUNT,
        )

        assertThat(result.activeRevision).isEqualTo(REPEATED_REVISION_COUNT.toLong())
        assertThat(result.retainedRevisions)
            .containsExactly((REPEATED_REVISION_COUNT - 1).toLong())
        assertThat(result.beforeCompaction.payloadRows)
            .isGreaterThan(result.afterCompaction.payloadRows)
        assertThat(result.afterCompaction.payloadRows)
            .isAtMost(ENTRY_COUNT * 2)
        assertThat(result.afterCompaction.searchPayloadRows)
            .isAtMost(ENTRY_COUNT)
        assertThat(result.afterCompaction.membershipRows)
            .isEqualTo(ENTRY_COUNT * 2)
        assertThat(result.cleanupPasses).isAtMost(MAX_REPEATED_CLEANUP_PASSES)
    }

    private companion object {
        const val ENTRY_COUNT = 120
        const val MAX_BOUNDED_CLEANUP_PASSES = 4
        const val SEARCH_MARKER_INDEX = 42
        const val REPEATED_REVISION_COUNT = 5
        const val MAX_REPEATED_CLEANUP_PASSES = 4
    }
}

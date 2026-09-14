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

    private companion object {
        const val ENTRY_COUNT = 120
        const val MAX_BOUNDED_CLEANUP_PASSES = 4
    }
}

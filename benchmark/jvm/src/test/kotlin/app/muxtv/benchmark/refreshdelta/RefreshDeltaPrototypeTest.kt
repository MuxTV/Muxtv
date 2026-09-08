package app.muxtv.benchmark.refreshdelta

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefreshDeltaPrototypeTest {
    private val prototype = RefreshDeltaPrototype()

    @Test
    fun unchangedCatalogKeepsEquivalentActiveDigestAndElidesCandidatePayloadWrites() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 3L)
        val comparison = prototype.compare(previous, previous)

        comparison.assertCorrectnessAgreement(expectedCount = 1_000)
        assertThat(comparison[RefreshDeltaVariant.A_CURRENT_MUXTV].writes.payloadWrites).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.payloadWrites).isEqualTo(0)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.membershipWrites).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.rowWrites).isEqualTo(0)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].preservedLogicalIdentityCount).isEqualTo(1_000)
    }

    @Test
    fun onePercentDeltaWritesOnlyChangedCandidatePayloadsAndPreservesIdentity() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 7L)
        val incoming = RefreshDeltaCorpus.contentDelta(previous, changedPercent = 1)
        val comparison = prototype.compare(previous, incoming)

        comparison.assertCorrectnessAgreement(expectedCount = 1_000)
        assertThat(comparison[RefreshDeltaVariant.A_CURRENT_MUXTV].writes.payloadWrites).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.payloadWrites).isEqualTo(10)
        assertThat(comparison[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.updatedRows).isEqualTo(10)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].preservedLogicalIdentityCount).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.C_OWNTV_REFERENCE].preservedLogicalIdentityCount).isEqualTo(1_000)
    }

    @Test
    fun tenAndHundredPercentDeltasScalePayloadWritesWithActualContentChanges() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 11L)

        val tenPercent = prototype.compare(
            previous,
            RefreshDeltaCorpus.contentDelta(previous, changedPercent = 10),
        )
        tenPercent.assertCorrectnessAgreement(expectedCount = 1_000)
        assertThat(tenPercent[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.payloadWrites).isEqualTo(100)
        assertThat(tenPercent[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.updatedRows).isEqualTo(100)

        val full = prototype.compare(
            previous,
            RefreshDeltaCorpus.contentDelta(previous, changedPercent = 100),
        )
        full.assertCorrectnessAgreement(expectedCount = 1_000)
        assertThat(full[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.payloadWrites).isEqualTo(1_000)
        assertThat(full[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.updatedRows).isEqualTo(1_000)
    }

    @Test
    fun reorderOnlyChangesMembershipOrderWithoutRewritingCandidatePayloads() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 13L)
        val incoming = RefreshDeltaCorpus.reverseOrder(previous)
        val comparison = prototype.compare(previous, incoming)

        comparison.assertCorrectnessAgreement(expectedCount = 1_000)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.payloadWrites).isEqualTo(0)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.membershipWrites).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.movedRows).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].preservedLogicalIdentityCount).isEqualTo(1_000)
    }

    @Test
    fun successfulRemovalPublishesSmallerCatalogButFailedRefreshPreservesPreviousGood() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 17L)
        val incoming = RefreshDeltaCorpus.removeTail(previous, count = 100)

        val published = prototype.compare(previous, incoming)
        published.assertCorrectnessAgreement(expectedCount = 900)
        assertThat(published[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.deletedRows).isEqualTo(100)

        val failed = prototype.compare(
            previous = previous,
            incoming = incoming.take(300),
            disposition = RefreshPublicationDisposition.FAILED,
        )
        failed.assertCorrectnessAgreement(expectedCount = 1_000)
        RefreshDeltaVariant.entries.forEach { variant ->
            assertThat(failed[variant].previousGoodPreserved).isTrue()
            assertThat(failed[variant].activeDigestSha256).isEqualTo(RefreshDeltaDigest.activeCatalog(previous))
        }
    }

    @Test
    fun staleGenerationCannotPublishAndLeavesPreviousGoodActive() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 19L)
        val incoming = RefreshDeltaCorpus.contentDelta(previous, changedPercent = 10)
        val comparison = prototype.compare(
            previous = previous,
            incoming = incoming,
            incomingGeneration = 4L,
            authoritativeGeneration = 5L,
        )

        comparison.assertCorrectnessAgreement(expectedCount = 1_000)
        RefreshDeltaVariant.entries.forEach { variant ->
            val result = comparison[variant]
            assertThat(result.staleGenerationRejected).isTrue()
            assertThat(result.previousGoodPreserved).isTrue()
            assertThat(result.activeDigestSha256).isEqualTo(RefreshDeltaDigest.activeCatalog(previous))
        }
    }

    @Test
    fun duplicateAndSameNameGroupCollisionsReceiveDeterministicUniqueLogicalKeys() {
        val first = RefreshDeltaCorpus.duplicateCollisionFixture()
        val second = RefreshDeltaCorpus.duplicateCollisionFixture()

        assertThat(first.map(RefreshDeltaItem::stableKey)).containsExactlyElementsIn(
            second.map(RefreshDeltaItem::stableKey),
        ).inOrder()
        assertThat(first.map(RefreshDeltaItem::stableKey).distinct()).hasSize(first.size)
        assertThat(first.map(RefreshDeltaItem::baseIdentityKey).distinct().size).isLessThan(first.size)
    }

    @Test
    fun tokenizedLocatorChurnChangesContentButNeverLogicalIdentityOrEvidence() {
        val previous = RefreshDeltaCorpus.baseline(size = 1_000, seed = 23L)
        val incoming = RefreshDeltaCorpus.tokenizedLocatorChurn(previous)
        val comparison = prototype.compare(previous, incoming)

        comparison.assertCorrectnessAgreement(expectedCount = 1_000)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].writes.payloadWrites).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.C_OWNTV_REFERENCE].writes.updatedRows).isEqualTo(1_000)
        assertThat(comparison[RefreshDeltaVariant.B_IMMUTABLE_COW].preservedLogicalIdentityCount).isEqualTo(1_000)
        assertThat(comparison.toString()).doesNotContain("token=")
        assertThat(comparison.toString()).doesNotContain("https://")
    }

    private fun RefreshDeltaComparison.assertCorrectnessAgreement(expectedCount: Int) {
        assertThat(correctnessPassed).isTrue()
        val results = RefreshDeltaVariant.entries.map(::get)
        assertThat(results.map(RefreshDeltaResult::activeCount).distinct()).containsExactly(expectedCount)
        assertThat(results.map(RefreshDeltaResult::activeDigestSha256).distinct()).hasSize(1)
    }
}

package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgMatchDecisionTest {
    @Test
    fun oneDistinctCanonicalIdMatchesEvenWhenEvidenceRowsRepeat() {
        val decision = collapseEpgMatchCandidates(
            canonicalChannelIds = listOf("canonical-1", "canonical-1"),
            reasonCode = EpgMatchReasonCode.EXACT_ID,
        )

        assertThat(decision).isEqualTo(
            EpgMatchResolution.Matched(
                canonicalChannelId = "canonical-1",
                reasonCode = EpgMatchReasonCode.EXACT_ID,
            ),
        )
    }

    @Test
    fun multipleDistinctCanonicalIdsAreAmbiguousIndependentOfInputOrder() {
        val first = collapseEpgMatchCandidates(
            canonicalChannelIds = listOf("canonical-2", "canonical-1", "canonical-2"),
            reasonCode = EpgMatchReasonCode.EXACT_TVG_NAME,
        )
        val second = collapseEpgMatchCandidates(
            canonicalChannelIds = listOf("canonical-1", "canonical-2"),
            reasonCode = EpgMatchReasonCode.EXACT_TVG_NAME,
        )

        val expected = EpgMatchResolution.Ambiguous(
            reasonCode = EpgMatchReasonCode.EXACT_TVG_NAME,
            candidateCount = 2,
        )
        assertThat(first).isEqualTo(expected)
        assertThat(second).isEqualTo(expected)
    }

    @Test
    fun emptyEvidenceReturnsNullSoTheMatcherCanTryWeakerEvidence() {
        assertThat(
            collapseEpgMatchCandidates(
                canonicalChannelIds = emptyList(),
                reasonCode = EpgMatchReasonCode.EXACT_RAW_NAME,
            ),
        ).isNull()
    }

    @Test
    fun unresolvedDecisionUsesStableNoMatchReason() {
        assertThat(unresolvedEpgMatch()).isEqualTo(
            EpgMatchResolution.Unresolved(EpgMatchReasonCode.NO_MATCH),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCanonicalCandidateIsRejected() {
        collapseEpgMatchCandidates(
            canonicalChannelIds = listOf("canonical-1", " "),
            reasonCode = EpgMatchReasonCode.EXACT_ID,
        )
    }
}

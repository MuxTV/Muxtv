package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgGuideRepositoryTest {
    @Test
    fun queryAcceptsBoundedDistinctCanonicalIds() {
        val query = NowNextQuery(
            profileId = "profile-1",
            canonicalChannelIds = listOf("canonical-1", "canonical-2"),
            nowEpochMillis = 1_000,
        )

        assertThat(query.canonicalChannelIds).containsExactly("canonical-1", "canonical-2").inOrder()
        assertThat(query.toString()).doesNotContain("profile-1")
        assertThat(query.toString()).doesNotContain("canonical-1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun queryRejectsDuplicateCanonicalIds() {
        NowNextQuery(
            profileId = "profile-1",
            canonicalChannelIds = listOf("canonical-1", "canonical-1"),
            nowEpochMillis = 1_000,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun queryRejectsUnboundedCanonicalIdBatch() {
        NowNextQuery(
            profileId = "profile-1",
            canonicalChannelIds = List(NowNextQuery.MAX_CHANNEL_IDS + 1) { "canonical-$it" },
            nowEpochMillis = 1_000,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun queryRejectsBlankCanonicalId() {
        NowNextQuery(
            profileId = "profile-1",
            canonicalChannelIds = listOf("canonical-1", " "),
            nowEpochMillis = 1_000,
        )
    }

    @Test
    fun programmeRequiresForwardExplicitEndAndRedactsTitle() {
        val programme = GuideProgramme(
            startEpochMillis = 1_000,
            endEpochMillis = 2_000,
            title = "Sensitive programme title",
        )

        assertThat(programme.toString()).doesNotContain("Sensitive programme title")
    }

    @Test(expected = IllegalArgumentException::class)
    fun programmeRejectsNonForwardExplicitEnd() {
        GuideProgramme(
            startEpochMillis = 1_000,
            endEpochMillis = 1_000,
            title = null,
        )
    }

    @Test
    fun sourceConflictCarriesNoProgrammePayload() {
        val projection = ChannelNowNext(
            canonicalChannelId = "canonical-1",
            state = GuideProjectionState.SOURCE_CONFLICT,
            current = null,
            next = null,
            nextBoundaryEpochMillis = null,
        )

        assertThat(projection.state).isEqualTo(GuideProjectionState.SOURCE_CONFLICT)
        assertThat(projection.toString()).doesNotContain("canonical-1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sourceConflictRejectsProgrammePayload() {
        ChannelNowNext(
            canonicalChannelId = "canonical-1",
            state = GuideProjectionState.SOURCE_CONFLICT,
            current = GuideProgramme(1_000, 2_000, "Programme"),
            next = null,
            nextBoundaryEpochMillis = 2_000,
        )
    }
}

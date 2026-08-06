package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class GuideWindowRepositoryContractTest {
    @Test
    fun channelQueryAcceptsBoundedCursorAndRedactsIdentity() {
        val cursor = GuideChannelCursor(
            channelNumber = 7,
            displayName = "Sensitive channel name",
            canonicalChannelId = "canonical-secret",
        )
        val query = GuideChannelWindowQuery(
            profileId = "profile-secret",
            after = cursor,
            limit = 25,
        )

        assertThat(query.after).isEqualTo(cursor)
        assertThat(query.limit).isEqualTo(25)
        assertThat(query.toString()).doesNotContain("profile-secret")
        assertThat(query.toString()).doesNotContain("Sensitive channel name")
        assertThat(query.toString()).doesNotContain("canonical-secret")
        assertThat(cursor.toString()).doesNotContain("Sensitive channel name")
        assertThat(cursor.toString()).doesNotContain("canonical-secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun channelQueryRejectsLimitAboveMaximum() {
        GuideChannelWindowQuery(
            profileId = "profile-1",
            limit = GuideChannelWindowQuery.MAX_LIMIT + 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun cursorRejectsBlankStableIdentity() {
        GuideChannelCursor(
            channelNumber = null,
            displayName = "Channel",
            canonicalChannelId = " ",
        )
    }

    @Test
    fun truncatedChannelWindowRequiresContinuationCursor() {
        val channel = summary("canonical-1")
        val cursor = GuideChannelCursor(null, "Channel", "canonical-1")
        val window = GuideChannelWindow(
            channels = listOf(channel),
            nextCursor = cursor,
            isTruncated = true,
        )

        assertThat(window.channels).containsExactly(channel)
        assertThat(window.nextCursor).isEqualTo(cursor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun completeChannelWindowRejectsContinuationCursor() {
        GuideChannelWindow(
            channels = listOf(summary("canonical-1")),
            nextCursor = GuideChannelCursor(null, "Channel", "canonical-1"),
            isTruncated = false,
        )
    }

    @Test
    fun programmeQueryDefensivelyCopiesBoundedIdsAndRedactsThem() {
        val source = mutableListOf("canonical-1", "canonical-2")
        val query = GuideProgrammeWindowQuery(
            profileId = "profile-secret",
            canonicalChannelIds = source,
            fromEpochMillis = 1_000,
            toEpochMillis = 3_000,
            limit = 100,
        )
        source.clear()

        assertThat(query.canonicalChannelIds)
            .containsExactly("canonical-1", "canonical-2")
            .inOrder()
        assertThat(query.toString()).doesNotContain("profile-secret")
        assertThat(query.toString()).doesNotContain("canonical-1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun programmeQueryRejectsDuplicateIds() {
        GuideProgrammeWindowQuery(
            profileId = "profile-1",
            canonicalChannelIds = listOf("canonical-1", "canonical-1"),
            fromEpochMillis = 1_000,
            toEpochMillis = 2_000,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun programmeQueryRejectsSpanAboveTwelveHours() {
        GuideProgrammeWindowQuery(
            profileId = "profile-1",
            canonicalChannelIds = listOf("canonical-1"),
            fromEpochMillis = 0,
            toEpochMillis = GuideProgrammeWindowQuery.MAX_SPAN_MILLIS + 1,
        )
    }

    @Test
    fun programmeCellHasStableKeyAndRedactsPayload() {
        val cell = GuideProgrammeCell(
            key = GuideProgrammeKey(
                epgSourceId = "epg-secret",
                epgRevisionNumber = 3,
                sequenceNumber = 9,
            ),
            startEpochMillis = 1_000,
            endEpochMillis = 2_000,
            title = "Sensitive programme",
        )

        assertThat(cell.endEpochMillis).isGreaterThan(cell.startEpochMillis)
        assertThat(cell.toString()).doesNotContain("Sensitive programme")
        assertThat(cell.toString()).doesNotContain("epg-secret")
        assertThat(cell.key.toString()).doesNotContain("epg-secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun noGuideChannelRejectsProgrammePayload() {
        ChannelGuideProgrammeWindow(
            canonicalChannelId = "canonical-1",
            state = GuideProjectionState.NO_GUIDE,
            programmes = listOf(
                GuideProgrammeCell(
                    key = GuideProgrammeKey("epg-1", 1, 1),
                    startEpochMillis = 1_000,
                    endEpochMillis = 2_000,
                    title = null,
                ),
            ),
        )
    }

    @Test
    fun repositoryExposesPayloadFreeInvalidationSignal() = runBlocking {
        val repository = object : GuideWindowRepository {
            override suspend fun getChannelWindow(
                query: GuideChannelWindowQuery,
            ): GuideChannelWindow = GuideChannelWindow(emptyList(), null, false)

            override suspend fun getProgrammeWindow(
                query: GuideProgrammeWindowQuery,
            ): GuideProgrammeWindow = GuideProgrammeWindow(emptyList(), false)

            override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
        }

        assertThat(repository.observeDataChanges().first()).isEqualTo(Unit)
    }

    private fun summary(channelId: String): PlayableChannelSummary = PlayableChannelSummary(
        channelId = channelId,
        displayName = "Channel",
        logoUrl = null,
        groupTitle = null,
        channelNumber = null,
        isFavorite = false,
        variantCount = 1,
    )
}

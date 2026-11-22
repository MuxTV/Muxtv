package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProjectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuidePlaybackSelectionTest {
    @Test
    fun `status cell launches live`() {
        val selection = guidePlaybackSelection(
            channelId = CHANNEL_ID,
            cell = statusCell(),
            nowEpochMillis = NOW,
        )

        assertThat(selection).isEqualTo(GuidePlaybackSelection.Live(CHANNEL_ID))
    }

    @Test
    fun `current programme launches live`() {
        val selection = guidePlaybackSelection(
            channelId = CHANNEL_ID,
            cell = programmeCell(
                originalStartEpochMillis = NOW - 30 * MINUTE_MILLIS,
                originalEndEpochMillis = NOW + 30 * MINUTE_MILLIS,
            ),
            nowEpochMillis = NOW,
        )

        assertThat(selection).isEqualTo(GuidePlaybackSelection.Live(CHANNEL_ID))
    }

    @Test
    fun `completed programme launches catchup using original bounds`() {
        val originalStart = NOW - 2 * HOUR_MILLIS
        val originalEnd = NOW - HOUR_MILLIS
        val cell = programmeCell(
            originalStartEpochMillis = originalStart,
            originalEndEpochMillis = originalEnd,
            visibleStartEpochMillis = NOW - 90 * MINUTE_MILLIS,
            visibleEndEpochMillis = originalEnd,
        )

        val selection = guidePlaybackSelection(
            channelId = CHANNEL_ID,
            cell = cell,
            nowEpochMillis = NOW,
        ) as GuidePlaybackSelection.CatchupProgram

        assertThat(selection.channelId).isEqualTo(CHANNEL_ID)
        assertThat(selection.startEpochMillis).isEqualTo(originalStart)
        assertThat(selection.endEpochMillis).isEqualTo(originalEnd)
        assertThat(selection.programmeId).isNotEmpty()
        assertThat(selection.programmeId.length).isAtMost(80)
        assertThat(selection.programmeId).doesNotContain(CHANNEL_ID)
        assertThat(selection.programmeId).doesNotContain(EPG_SOURCE_ID)
        assertThat(selection.programmeId).doesNotContain("Morning News")
    }

    @Test
    fun `future programme does not launch playback`() {
        val selection = guidePlaybackSelection(
            channelId = CHANNEL_ID,
            cell = programmeCell(
                originalStartEpochMillis = NOW + MINUTE_MILLIS,
                originalEndEpochMillis = NOW + HOUR_MILLIS,
            ),
            nowEpochMillis = NOW,
        )

        assertThat(selection).isNull()
    }

    @Test
    fun `programme identity is deterministic and channel scoped`() {
        val cell = programmeCell(
            originalStartEpochMillis = NOW - HOUR_MILLIS,
            originalEndEpochMillis = NOW - MINUTE_MILLIS,
        )

        val first = guidePlaybackSelection(
            channelId = CHANNEL_ID,
            cell = cell,
            nowEpochMillis = NOW,
        ) as GuidePlaybackSelection.CatchupProgram
        val second = guidePlaybackSelection(
            channelId = CHANNEL_ID,
            cell = cell,
            nowEpochMillis = NOW,
        ) as GuidePlaybackSelection.CatchupProgram
        val otherChannel = guidePlaybackSelection(
            channelId = "channel-other",
            cell = cell,
            nowEpochMillis = NOW,
        ) as GuidePlaybackSelection.CatchupProgram

        assertThat(first.programmeId).isEqualTo(second.programmeId)
        assertThat(first.programmeId).isNotEqualTo(otherChannel.programmeId)
    }

    private fun programmeCell(
        originalStartEpochMillis: Long,
        originalEndEpochMillis: Long,
        visibleStartEpochMillis: Long = originalStartEpochMillis,
        visibleEndEpochMillis: Long = originalEndEpochMillis,
    ): GuideCellProjection = GuideCellProjection(
        programmeKey = GuideProgrammeKey(
            epgSourceId = EPG_SOURCE_ID,
            epgRevisionNumber = 7,
            sequenceNumber = 42,
        ),
        state = GuideProjectionState.READY,
        startEpochMillis = visibleStartEpochMillis,
        endEpochMillis = visibleEndEpochMillis,
        originalStartEpochMillis = originalStartEpochMillis,
        originalEndEpochMillis = originalEndEpochMillis,
        title = "Morning News",
        timeLabel = "10:00–11:00",
        detailLabel = "News · Morning News · 10:00–11:00",
    )

    private fun statusCell(): GuideCellProjection = GuideCellProjection(
        programmeKey = null,
        state = GuideProjectionState.NO_GUIDE,
        startEpochMillis = NOW - MINUTE_MILLIS,
        endEpochMillis = NOW + MINUTE_MILLIS,
        originalStartEpochMillis = null,
        originalEndEpochMillis = null,
        title = "No guide",
        timeLabel = null,
        detailLabel = "News · no guide",
    )

    private companion object {
        const val CHANNEL_ID = "channel-news"
        const val EPG_SOURCE_ID = "epg-private-source"
        const val NOW = 1_800_000_000_000L
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    }
}

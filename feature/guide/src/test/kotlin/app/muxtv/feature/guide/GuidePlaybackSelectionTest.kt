package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class GuidePlaybackSelectionTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun completedProgrammeUsesOriginalProgrammeBoundsInsteadOfViewportClipping() {
        val originalStart = VIEWPORT_FROM - 30 * MINUTE_MILLIS
        val originalEnd = VIEWPORT_FROM + 30 * MINUTE_MILLIS
        val row = projectGuideRow(
            channel = channel(),
            state = GuideProjectionState.READY,
            programmes = listOf(
                GuideProgrammeCell(
                    key = key(42),
                    startEpochMillis = originalStart,
                    endEpochMillis = originalEnd,
                    title = "Новости",
                ),
            ),
            viewportFromEpochMillis = VIEWPORT_FROM,
            viewportToEpochMillis = VIEWPORT_TO,
            zoneId = zone,
        )
        val cell = row.cells.single()

        val selection = selectGuidePlayback(
            channelId = CHANNEL_ID,
            cell = cell,
            nowEpochMillis = originalEnd + 1,
        )

        assertThat(selection).isInstanceOf(GuidePlaybackSelection.CatchupProgram::class.java)
        val catchup = selection as GuidePlaybackSelection.CatchupProgram
        assertThat(catchup.channelId).isEqualTo(CHANNEL_ID)
        assertThat(catchup.startEpochMillis).isEqualTo(originalStart)
        assertThat(catchup.endEpochMillis).isEqualTo(originalEnd)
        assertThat(catchup.programmeId).doesNotContain("secret-epg")
        assertThat(catchup.programmeId.length).isAtMost(512)
        assertThat(cell.startEpochMillis).isEqualTo(VIEWPORT_FROM)
    }

    @Test
    fun currentProgrammeLaunchesLive() {
        val row = programmeRow(
            startEpochMillis = VIEWPORT_FROM + HOUR_MILLIS,
            endEpochMillis = VIEWPORT_FROM + 2 * HOUR_MILLIS,
        )

        val selection = selectGuidePlayback(
            channelId = CHANNEL_ID,
            cell = row.cells.single(),
            nowEpochMillis = VIEWPORT_FROM + HOUR_MILLIS + 1,
        )

        assertThat(selection).isEqualTo(GuidePlaybackSelection.Live(CHANNEL_ID))
    }

    @Test
    fun futureProgrammeDoesNotLaunchPlayback() {
        val row = programmeRow(
            startEpochMillis = VIEWPORT_FROM + 2 * HOUR_MILLIS,
            endEpochMillis = VIEWPORT_FROM + 3 * HOUR_MILLIS,
        )

        val selection = selectGuidePlayback(
            channelId = CHANNEL_ID,
            cell = row.cells.single(),
            nowEpochMillis = VIEWPORT_FROM + HOUR_MILLIS,
        )

        assertThat(selection).isNull()
    }

    @Test
    fun statusCellKeepsExistingLiveBehaviour() {
        val row = projectGuideRow(
            channel = channel(),
            state = GuideProjectionState.NO_GUIDE,
            programmes = emptyList(),
            viewportFromEpochMillis = VIEWPORT_FROM,
            viewportToEpochMillis = VIEWPORT_TO,
            zoneId = zone,
        )

        val selection = selectGuidePlayback(
            channelId = CHANNEL_ID,
            cell = row.cells.single(),
            nowEpochMillis = VIEWPORT_FROM,
        )

        assertThat(selection).isEqualTo(GuidePlaybackSelection.Live(CHANNEL_ID))
    }

    private fun programmeRow(
        startEpochMillis: Long,
        endEpochMillis: Long,
    ) = projectGuideRow(
        channel = channel(),
        state = GuideProjectionState.READY,
        programmes = listOf(
            GuideProgrammeCell(
                key = key(7),
                startEpochMillis = startEpochMillis,
                endEpochMillis = endEpochMillis,
                title = "Передача",
            ),
        ),
        viewportFromEpochMillis = VIEWPORT_FROM,
        viewportToEpochMillis = VIEWPORT_TO,
        zoneId = zone,
    )

    private fun channel() = PlayableChannelSummary(
        channelId = CHANNEL_ID,
        displayName = "Канал",
        logoUrl = null,
        groupTitle = null,
        channelNumber = null,
        isFavorite = false,
        variantCount = 1,
    )

    private fun key(sequence: Long) = GuideProgrammeKey(
        epgSourceId = "secret-epg",
        epgRevisionNumber = 9,
        sequenceNumber = sequence,
    )

    private companion object {
        const val CHANNEL_ID = "channel-a"
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60L * MINUTE_MILLIS
        const val VIEWPORT_FROM = 1_800_000_000_000L
        const val VIEWPORT_TO = VIEWPORT_FROM + 6 * HOUR_MILLIS
    }
}

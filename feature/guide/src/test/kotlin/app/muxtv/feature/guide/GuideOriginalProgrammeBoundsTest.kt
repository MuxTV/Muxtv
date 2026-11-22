package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class GuideOriginalProgrammeBoundsTest {
    @Test
    fun `viewport clipping preserves original programme bounds for playback semantics`() {
        val viewportFrom = 1_800_000_000_000L
        val originalStart = viewportFrom - 30 * MINUTE_MILLIS
        val originalEnd = viewportFrom + 30 * MINUTE_MILLIS
        val row = projectGuideRow(
            channel = PlayableChannelSummary(
                channelId = CHANNEL_ID,
                displayName = "News",
                logoUrl = null,
                groupTitle = null,
                channelNumber = null,
                isFavorite = false,
                variantCount = 1,
            ),
            state = GuideProjectionState.READY,
            programmes = listOf(
                GuideProgrammeCell(
                    key = GuideProgrammeKey(
                        epgSourceId = "epg-private-source",
                        epgRevisionNumber = 7,
                        sequenceNumber = 42,
                    ),
                    startEpochMillis = originalStart,
                    endEpochMillis = originalEnd,
                    title = "Morning News",
                ),
            ),
            viewportFromEpochMillis = viewportFrom,
            viewportToEpochMillis = viewportFrom + 6 * HOUR_MILLIS,
            zoneId = ZoneId.of("UTC"),
        )

        val cell = row.cells.single()
        assertThat(cell.startEpochMillis).isEqualTo(viewportFrom)
        assertThat(cell.endEpochMillis).isEqualTo(originalEnd)
        assertThat(cell.originalStartEpochMillis).isEqualTo(originalStart)
        assertThat(cell.originalEndEpochMillis).isEqualTo(originalEnd)
    }

    private companion object {
        const val CHANNEL_ID = "channel-news"
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    }
}

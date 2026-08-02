package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelRowProjectionTest {
    @Test
    fun `joins guide by canonical channel id while preserving channel order`() {
        val channels = listOf(
            channel("channel-b", "Beta"),
            channel("channel-a", "Alpha"),
        )
        val guide = listOf(
            ready(
                channelId = "channel-a",
                currentTitle = "Alpha current",
                nextTitle = "Alpha next",
                boundary = 2_000,
            ),
            ready(
                channelId = "channel-b",
                currentTitle = "Beta current",
                nextTitle = null,
                boundary = 1_500,
            ),
        )

        val rows = projectChannelRows(channels, guide)

        assertThat(rows.map(ChannelRowProjection::channelId))
            .containsExactly("channel-b", "channel-a").inOrder()
        assertThat(rows[0].currentTitle).isEqualTo("Beta current")
        assertThat(rows[0].nextTitle).isNull()
        assertThat(rows[1].currentTitle).isEqualTo("Alpha current")
        assertThat(rows[1].nextTitle).isEqualTo("Alpha next")
    }

    @Test
    fun `no guide and missing projection never invent programme content`() {
        val channels = listOf(
            channel("channel-a", "Alpha"),
            channel("channel-b", "Beta"),
        )
        val guide = listOf(
            ChannelNowNext(
                canonicalChannelId = "channel-a",
                state = GuideProjectionState.NO_GUIDE,
                current = null,
                next = null,
                nextBoundaryEpochMillis = null,
            ),
        )

        val rows = projectChannelRows(channels, guide)

        assertThat(rows[0].guideState).isEqualTo(GuideProjectionState.NO_GUIDE)
        assertThat(rows[0].currentTitle).isNull()
        assertThat(rows[0].nextTitle).isNull()
        assertThat(rows[1].guideState).isEqualTo(GuideProjectionState.NO_GUIDE)
        assertThat(rows[1].currentTitle).isNull()
        assertThat(rows[1].nextTitle).isNull()
    }

    @Test
    fun `source conflict remains explicit without programme payload`() {
        val rows = projectChannelRows(
            channels = listOf(channel("channel-a", "Alpha")),
            guide = listOf(
                ChannelNowNext(
                    canonicalChannelId = "channel-a",
                    state = GuideProjectionState.SOURCE_CONFLICT,
                    current = null,
                    next = null,
                    nextBoundaryEpochMillis = null,
                ),
            ),
        )

        assertThat(rows.single().guideState).isEqualTo(GuideProjectionState.SOURCE_CONFLICT)
        assertThat(rows.single().currentTitle).isNull()
        assertThat(rows.single().nextTitle).isNull()
    }

    @Test
    fun `active playback is joined by canonical channel identity`() {
        val rows = projectChannelRows(
            channels = listOf(
                channel("channel-a", "Alpha"),
                channel("channel-b", "Beta"),
            ),
            guide = emptyList(),
            playbackSessionState = PlaybackSessionState(
                channelId = "channel-b",
                phase = PlaybackSessionPhase.READY,
                isPlaying = true,
            ),
        )

        assertThat(rows[0].isCurrentPlayback).isFalse()
        assertThat(rows[0].isPlaying).isFalse()
        assertThat(rows[1].isCurrentPlayback).isTrue()
        assertThat(rows[1].isPlaying).isTrue()
    }

    @Test
    fun `idle media identity is not presented as current playback`() {
        val row = projectChannelRows(
            channels = listOf(channel("channel-a", "Alpha")),
            guide = emptyList(),
            playbackSessionState = PlaybackSessionState(
                channelId = "channel-a",
                phase = PlaybackSessionPhase.IDLE,
                isPlaying = false,
            ),
        ).single()

        assertThat(row.isCurrentPlayback).isFalse()
        assertThat(row.isPlaying).isFalse()
    }

    @Test
    fun `earliest future boundary ignores past and null boundaries`() {
        val rows = listOf(
            row("past", boundary = 900),
            row("later", boundary = 1_800),
            row("earliest", boundary = 1_200),
            row("none", boundary = null),
        )

        assertThat(earliestFutureGuideBoundary(rows, nowEpochMillis = 1_000))
            .isEqualTo(1_200)
        assertThat(earliestFutureGuideBoundary(rows.reversed(), nowEpochMillis = 1_000))
            .isEqualTo(1_200)
    }

    @Test
    fun `no future boundary returns null`() {
        val rows = listOf(
            row("past", boundary = 999),
            row("now", boundary = 1_000),
            row("none", boundary = null),
        )

        assertThat(earliestFutureGuideBoundary(rows, nowEpochMillis = 1_000)).isNull()
    }

    private fun channel(id: String, name: String): PlayableChannelSummary =
        PlayableChannelSummary(
            channelId = id,
            displayName = name,
            logoUrl = null,
            groupTitle = null,
            channelNumber = null,
            isFavorite = false,
            variantCount = 1,
        )

    private fun ready(
        channelId: String,
        currentTitle: String?,
        nextTitle: String?,
        boundary: Long?,
    ): ChannelNowNext = ChannelNowNext(
        canonicalChannelId = channelId,
        state = GuideProjectionState.READY,
        current = GuideProgramme(
            startEpochMillis = 1_000,
            endEpochMillis = boundary,
            title = currentTitle,
        ),
        next = nextTitle?.let { title ->
            GuideProgramme(
                startEpochMillis = boundary ?: 2_000,
                endEpochMillis = (boundary ?: 2_000) + 1_000,
                title = title,
            )
        },
        nextBoundaryEpochMillis = boundary,
    )

    private fun row(channelId: String, boundary: Long?): ChannelRowProjection =
        ChannelRowProjection(
            channel = channel(channelId, channelId),
            guideState = GuideProjectionState.READY,
            currentTitle = null,
            nextTitle = null,
            nextBoundaryEpochMillis = boundary,
            isCurrentPlayback = false,
            isPlaying = false,
        )
}

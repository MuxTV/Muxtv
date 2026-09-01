package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackIntentTimelineContractTest {
    @Test
    fun `live intent carries only canonical channel identity`() {
        val intent = PlaybackIntent.Live(channelId = CHANNEL_ID)

        assertThat(intent.channelId).isEqualTo(CHANNEL_ID)
        assertThat(intent.toString()).doesNotContain(CHANNEL_ID)
    }

    @Test
    fun `catchup programme carries explicit utc interval and redacts identities`() {
        val intent = PlaybackIntent.CatchupProgram(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            startEpochMillis = PROGRAMME_START,
            endEpochMillis = PROGRAMME_END,
        )

        assertThat(intent.channelId).isEqualTo(CHANNEL_ID)
        assertThat(intent.programmeId).isEqualTo(PROGRAMME_ID)
        assertThat(intent.startEpochMillis).isEqualTo(PROGRAMME_START)
        assertThat(intent.endEpochMillis).isEqualTo(PROGRAMME_END)
        assertThat(intent.toString()).doesNotContain(CHANNEL_ID)
        assertThat(intent.toString()).doesNotContain(PROGRAMME_ID)
    }

    @Test
    fun `catchup position carries absolute utc position`() {
        val intent = PlaybackIntent.CatchupPosition(
            channelId = CHANNEL_ID,
            positionEpochMillis = PROGRAMME_START + 30_000,
        )

        assertThat(intent.channelId).isEqualTo(CHANNEL_ID)
        assertThat(intent.positionEpochMillis).isEqualTo(PROGRAMME_START + 30_000)
        assertThat(intent.toString()).doesNotContain(CHANNEL_ID)
    }

    @Test
    fun `intent identities reject blank oversized and control characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.Live(channelId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.Live(channelId = "x".repeat(513))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.CatchupProgram(
                channelId = CHANNEL_ID,
                programmeId = "programme\nsecret",
                startEpochMillis = PROGRAMME_START,
                endEpochMillis = PROGRAMME_END,
            )
        }
    }

    @Test
    fun `catchup programme rejects empty or inverted interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.CatchupProgram(
                channelId = CHANNEL_ID,
                programmeId = PROGRAMME_ID,
                startEpochMillis = PROGRAMME_START,
                endEpochMillis = PROGRAMME_START,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackIntent.CatchupProgram(
                channelId = CHANNEL_ID,
                programmeId = PROGRAMME_ID,
                startEpochMillis = PROGRAMME_END,
                endEpochMillis = PROGRAMME_START,
            )
        }
    }

    @Test
    fun `resolved timeline preserves bounded normalized archive semantics`() {
        val timeline = ResolvedPlaybackTimeline(
            windowStartEpochMillis = WINDOW_START,
            windowEndEpochMillis = WINDOW_END,
            programmeStartEpochMillis = PROGRAMME_START,
            programmeEndEpochMillis = PROGRAMME_END,
            initialPositionEpochMillis = PROGRAMME_START + 30_000,
            correctionMillis = 3_600_000,
            granularityMillis = 30_000,
            playAsLive = false,
        )

        assertThat(timeline.windowStartEpochMillis).isEqualTo(WINDOW_START)
        assertThat(timeline.windowEndEpochMillis).isEqualTo(WINDOW_END)
        assertThat(timeline.programmeStartEpochMillis).isEqualTo(PROGRAMME_START)
        assertThat(timeline.programmeEndEpochMillis).isEqualTo(PROGRAMME_END)
        assertThat(timeline.initialPositionEpochMillis).isEqualTo(PROGRAMME_START + 30_000)
        assertThat(timeline.correctionMillis).isEqualTo(3_600_000)
        assertThat(timeline.granularityMillis).isEqualTo(30_000)
        assertThat(timeline.playAsLive).isFalse()
    }

    @Test
    fun `resolved timeline permits position-only archive intent without fabricated programme`() {
        val timeline = ResolvedPlaybackTimeline(
            windowStartEpochMillis = WINDOW_START,
            windowEndEpochMillis = WINDOW_END,
            programmeStartEpochMillis = null,
            programmeEndEpochMillis = null,
            initialPositionEpochMillis = PROGRAMME_START,
            correctionMillis = 0,
            granularityMillis = null,
            playAsLive = true,
        )

        assertThat(timeline.programmeStartEpochMillis).isNull()
        assertThat(timeline.programmeEndEpochMillis).isNull()
        assertThat(timeline.playAsLive).isTrue()
    }

    @Test
    fun `resolved timeline rejects invalid window programme and initial position`() {
        assertThrows(IllegalArgumentException::class.java) {
            timeline(windowStart = WINDOW_END, windowEnd = WINDOW_START)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeline(
                programmeStart = WINDOW_START - 1,
                programmeEnd = PROGRAMME_END,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeline(initialPosition = WINDOW_END)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeline(granularity = 0)
        }
    }

    @Test
    fun `resolved timeline requires programme bounds as an atomic pair`() {
        assertThrows(IllegalArgumentException::class.java) {
            timeline(programmeStart = PROGRAMME_START, programmeEnd = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeline(programmeStart = null, programmeEnd = PROGRAMME_END)
        }
    }

    private fun timeline(
        windowStart: Long = WINDOW_START,
        windowEnd: Long = WINDOW_END,
        programmeStart: Long? = PROGRAMME_START,
        programmeEnd: Long? = PROGRAMME_END,
        initialPosition: Long = PROGRAMME_START,
        granularity: Long? = 30_000,
    ): ResolvedPlaybackTimeline = ResolvedPlaybackTimeline(
        windowStartEpochMillis = windowStart,
        windowEndEpochMillis = windowEnd,
        programmeStartEpochMillis = programmeStart,
        programmeEndEpochMillis = programmeEnd,
        initialPositionEpochMillis = initialPosition,
        correctionMillis = 0,
        granularityMillis = granularity,
        playAsLive = false,
    )

    private companion object {
        const val CHANNEL_ID = "canonical-channel-277"
        const val PROGRAMME_ID = "programme-277"
        const val WINDOW_START = 1_788_200_000_000L
        const val PROGRAMME_START = WINDOW_START + 3_600_000
        const val PROGRAMME_END = PROGRAMME_START + 1_800_000
        const val WINDOW_END = WINDOW_START + 7_200_000
    }
}

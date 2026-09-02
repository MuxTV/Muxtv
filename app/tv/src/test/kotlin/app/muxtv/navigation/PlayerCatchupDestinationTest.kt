package app.muxtv.navigation

import app.muxtv.feature.guide.GuidePlaybackSelection
import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerCatchupDestinationTest {
    @Test
    fun `legacy player destination remains live`() {
        val destination = AppDestination.Player(CHANNEL_ID)

        assertThat(destination.toPlaybackIntent())
            .isEqualTo(PlaybackIntent.Live(CHANNEL_ID))
    }

    @Test
    fun `catchup destination reconstructs provider neutral programme intent`() {
        val destination = AppDestination.Player(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            programmeStartEpochMillis = PROGRAMME_START,
            programmeEndEpochMillis = PROGRAMME_END,
        )

        assertThat(destination.toPlaybackIntent()).isEqualTo(
            PlaybackIntent.CatchupProgram(
                channelId = CHANNEL_ID,
                programmeId = PROGRAMME_ID,
                startEpochMillis = PROGRAMME_START,
                endEpochMillis = PROGRAMME_END,
            ),
        )
    }

    @Test
    fun `guide catchup selection maps to bounded player destination`() {
        val destination = GuidePlaybackSelection.CatchupProgram(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            startEpochMillis = PROGRAMME_START,
            endEpochMillis = PROGRAMME_END,
        ).toPlayerDestination()

        assertThat(destination).isEqualTo(
            AppDestination.Player(
                channelId = CHANNEL_ID,
                programmeId = PROGRAMME_ID,
                programmeStartEpochMillis = PROGRAMME_START,
                programmeEndEpochMillis = PROGRAMME_END,
            ),
        )
    }

    @Test
    fun `guide live selection maps to legacy player destination`() {
        assertThat(GuidePlaybackSelection.Live(CHANNEL_ID).toPlayerDestination())
            .isEqualTo(AppDestination.Player(CHANNEL_ID))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial catchup tuple is rejected`() {
        AppDestination.Player(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            programmeStartEpochMillis = PROGRAMME_START,
            programmeEndEpochMillis = null,
        )
    }

    private companion object {
        const val CHANNEL_ID = "channel-news"
        const val PROGRAMME_ID = "gp1_00112233445566778899aabbccddeeff"
        const val PROGRAMME_START = 1_800_000_000_000L
        const val PROGRAMME_END = PROGRAMME_START + 3_600_000L
    }
}

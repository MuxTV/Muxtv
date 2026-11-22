package app.muxtv.feature.player

import app.muxtv.player.PlaybackIntent
import app.muxtv.player.PlaybackStartRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerPlaybackIntentRequestTest {
    @Test
    fun `player request preserves catchup programme semantic intent`() {
        val intent = PlaybackIntent.CatchupProgram(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            startEpochMillis = PROGRAMME_START,
            endEpochMillis = PROGRAMME_END,
        )

        val request = playerPlaybackStartRequest(
            profileId = PROFILE_ID,
            intent = intent,
            preferredVariantId = VARIANT_ID,
        )

        assertThat(request).isEqualTo(
            PlaybackStartRequest(
                profileId = PROFILE_ID,
                intent = intent,
                preferredVariantId = VARIANT_ID,
            ),
        )
    }

    @Test
    fun `player request keeps live semantics source compatible`() {
        val request = playerPlaybackStartRequest(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.Live(CHANNEL_ID),
            preferredVariantId = null,
        )

        assertThat(request).isEqualTo(PlaybackStartRequest(PROFILE_ID, CHANNEL_ID))
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-news"
        const val VARIANT_ID = "variant-primary"
        const val PROGRAMME_ID = "gp1_00112233445566778899aabbccddeeff"
        const val PROGRAMME_START = 1_800_000_000_000L
        const val PROGRAMME_END = PROGRAMME_START + 3_600_000L
    }
}

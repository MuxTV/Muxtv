package app.muxtv.di

import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackArchiveBindingTest {
    @Test
    fun `application archive resolver routes Xtream and preserves M3U fallback`() {
        val now = System.currentTimeMillis()
        val resolver = AppModule.providePlaybackArchiveResolver()

        val xtream = resolver.resolve(
            PlaybackArchiveRequest(
                intent = PlaybackIntent.CatchupProgram(
                    channelId = "xtream-channel",
                    programmeId = "xtream-programme",
                    startEpochMillis = now - (2 * HOUR_MILLIS),
                    endEpochMillis = now - HOUR_MILLIS,
                ),
                livePlaybackReference = "muxtv-provider://xtream/live/707/m3u8",
                metadata = PlaybackArchiveMetadata(
                    mode = "xtream",
                    source = null,
                    days = 7,
                    correction = null,
                ),
            ),
        )

        val m3uPosition = now - (3 * HOUR_MILLIS)
        val m3u = resolver.resolve(
            PlaybackArchiveRequest(
                intent = PlaybackIntent.CatchupPosition(
                    channelId = "m3u-channel",
                    positionEpochMillis = m3uPosition,
                ),
                livePlaybackReference = LIVE_LOCATOR,
                metadata = PlaybackArchiveMetadata(
                    mode = "append",
                    source = "&utc={utc}",
                    days = 7,
                    correction = "0",
                ),
            ),
        )

        assertThat(xtream).isInstanceOf(PlaybackArchiveResolution.Ready::class.java)
        assertThat((xtream as PlaybackArchiveResolution.Ready).locator)
            .startsWith("muxtv-provider://xtream/archive/707/")
        assertThat(m3u).isInstanceOf(PlaybackArchiveResolution.Ready::class.java)
        assertThat((m3u as PlaybackArchiveResolution.Ready).locator)
            .isEqualTo("$LIVE_LOCATOR&utc=${m3uPosition / SECOND_MILLIS}")
    }

    private companion object {
        const val SECOND_MILLIS = 1_000L
        const val HOUR_MILLIS = 60L * 60L * SECOND_MILLIS
        const val LIVE_LOCATOR = "https://streams.invalid/live.m3u8"
    }
}

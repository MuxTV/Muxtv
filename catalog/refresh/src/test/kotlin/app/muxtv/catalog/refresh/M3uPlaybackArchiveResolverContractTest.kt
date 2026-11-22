package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uPlaybackArchiveResolverContractTest {
    @Test
    fun providerNeutralRequestDelegatesToExistingM3uTransportMaterializer() {
        val nowEpochMillis = 1_800_000_000_000L
        val positionEpochMillis = nowEpochMillis - (3 * HOUR_MILLIS) + 999L
        val resolver = M3uPlaybackArchiveResolver(nowEpochMillis = { nowEpochMillis })

        val result = resolver.resolve(
            PlaybackArchiveRequest(
                intent = PlaybackIntent.CatchupPosition(
                    channelId = "channel-catchup",
                    positionEpochMillis = positionEpochMillis,
                ),
                livePlaybackReference = LIVE_LOCATOR,
                metadata = PlaybackArchiveMetadata(
                    mode = "append",
                    source = "&utc={utc}&token=$ARCHIVE_SECRET",
                    days = 7,
                    correction = "0",
                ),
            ),
        )

        val ready = result as PlaybackArchiveResolution.Ready
        val expectedUtcSeconds = positionEpochMillis / SECOND_MILLIS
        assertThat(ready.locator)
            .isEqualTo("$LIVE_LOCATOR&utc=$expectedUtcSeconds&token=$ARCHIVE_SECRET")
        assertThat(ready.timeline.initialPositionEpochMillis).isEqualTo(positionEpochMillis)
        assertThat(ready.toString()).doesNotContain(LIVE_SECRET)
        assertThat(ready.toString()).doesNotContain(ARCHIVE_SECRET)
    }

    @Test
    fun liveIntentRemainsNotApplicableAtProviderBoundary() {
        val resolver = M3uPlaybackArchiveResolver(nowEpochMillis = { 1_800_000_000_000L })

        val result = resolver.resolve(
            PlaybackArchiveRequest(
                intent = PlaybackIntent.Live(channelId = "channel-live"),
                livePlaybackReference = LIVE_LOCATOR,
                metadata = PlaybackArchiveMetadata(
                    mode = "append",
                    source = "&utc={utc}&token=$ARCHIVE_SECRET",
                    days = 7,
                    correction = "0",
                ),
            ),
        )

        assertThat(result).isEqualTo(PlaybackArchiveResolution.NotApplicable)
    }

    private companion object {
        const val SECOND_MILLIS = 1_000L
        const val HOUR_MILLIS = 60 * 60 * SECOND_MILLIS
        const val LIVE_SECRET = "TEST_LIVE_SECRET"
        const val ARCHIVE_SECRET = "TEST_ARCHIVE_SECRET"
        const val LIVE_LOCATOR = "https://streams.invalid/live.m3u8?token=$LIVE_SECRET"
    }
}

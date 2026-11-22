package app.muxtv.catalog.refresh

import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uCatchupXtreamPathRegressionTest {
    @Test
    fun xtreamRootCredentialPathMaterializesPhpArchive() {
        val result = resolver().resolve(
            intent = programme(),
            liveLocator = "https://panel.invalid/TEST_USER/TEST_PASS/707.m3u8",
            metadata = xtreamMetadata(),
        )

        val ready = result as M3uCatchupTransportResolution.Ready
        assertThat(ready.locator).isEqualTo(
            "https://panel.invalid/streaming/timeshift.php" +
                "?username=TEST_USER&password=TEST_PASS&stream=707" +
                "&start=2026-09-02%3A10-15&duration=60",
        )
        assertThat(ready.timeline.granularityMillis).isEqualTo(MINUTE_MILLIS)
        assertThat(ready.initialMediaPositionMillis).isEqualTo(0L)
        assertThat(result.toString()).doesNotContain("TEST_PASS")
    }

    @Test
    fun xtreamUnexpectedPathStillFailsClosed() {
        val result = resolver().resolve(
            intent = programme(),
            liveLocator = "https://panel.invalid/prefix/TEST_USER/TEST_PASS/707.ts",
            metadata = xtreamMetadata(),
        )

        assertThat(result).isEqualTo(
            M3uCatchupTransportResolution.Unavailable(
                M3uCatchupUnavailableReason.INVALID_METADATA,
            ),
        )
    }

    private fun resolver() = M3uCatchupTransportResolver(nowEpochMillis = { NOW_MILLIS })

    private fun programme() = PlaybackIntent.CatchupProgram(
        channelId = "channel-xtream-root",
        programmeId = "programme-xtream-root",
        startEpochMillis = START_MILLIS,
        endEpochMillis = END_MILLIS,
    )

    private fun xtreamMetadata() = M3uCatchupMetadata(
        mode = "xtream",
        source = null,
        days = 7,
        correction = "0",
    )

    private companion object {
        const val NOW_MILLIS = 1_788_352_200_000L
        const val START_MILLIS = 1_788_344_100_000L
        const val END_MILLIS = 1_788_347_700_000L
    }
}

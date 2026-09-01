package app.muxtv.catalog.refresh

import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uCatchupTransportResolverContractTest {
    @Test
    fun appendUtcProgrammeMaterializesCorrectedEpochSecondsAndRedactsTransport() {
        val nowEpochMillis = 1_800_000_000_000L
        val programmeStart = nowEpochMillis - (2 * HOUR_MILLIS)
        val programmeEnd = nowEpochMillis - HOUR_MILLIS
        val result = M3uCatchupTransportResolver(nowEpochMillis = { nowEpochMillis }).resolve(
            intent = PlaybackIntent.CatchupProgram(
                channelId = "channel-catchup",
                programmeId = "programme-catchup",
                startEpochMillis = programmeStart,
                endEpochMillis = programmeEnd,
            ),
            liveLocator = LIVE_LOCATOR,
            metadata = supportedMetadata(correction = "+2.0"),
        )

        val ready = result as M3uCatchupTransportResolution.Ready
        val expectedUtcSeconds = (programmeStart - (2 * HOUR_MILLIS)) / SECOND_MILLIS
        assertThat(ready.locator)
            .isEqualTo("$LIVE_LOCATOR?utc=$expectedUtcSeconds&token=TEST_CATCHUP_SECRET")
        assertThat(ready.timeline.initialPositionEpochMillis).isEqualTo(programmeStart)
        assertThat(ready.timeline.correctionMillis).isEqualTo(2 * HOUR_MILLIS)
        assertThat(result.toString()).doesNotContain("TEST_CATCHUP_SECRET")
    }

    @Test
    fun catchupPositionRoundsUtcTokenDownToSecondGranularity() {
        val nowEpochMillis = 1_800_000_000_000L
        val positionEpochMillis = nowEpochMillis - (3 * HOUR_MILLIS) + 999L
        val result = M3uCatchupTransportResolver(nowEpochMillis = { nowEpochMillis }).resolve(
            intent = PlaybackIntent.CatchupPosition(
                channelId = "channel-catchup",
                positionEpochMillis = positionEpochMillis,
            ),
            liveLocator = LIVE_LOCATOR,
            metadata = supportedMetadata(correction = "0"),
        )

        val ready = result as M3uCatchupTransportResolution.Ready
        val expectedUtcSeconds = positionEpochMillis / SECOND_MILLIS
        assertThat(ready.locator)
            .isEqualTo("$LIVE_LOCATOR?utc=$expectedUtcSeconds&token=TEST_CATCHUP_SECRET")
        assertThat(ready.timeline.initialPositionEpochMillis).isEqualTo(positionEpochMillis)
    }

    @Test
    fun correctionCannotMoveMaterializedStartOutsideRetention() {
        val nowEpochMillis = 1_800_000_000_000L
        val windowStart = nowEpochMillis - (7 * DAY_MILLIS)
        val result = M3uCatchupTransportResolver(nowEpochMillis = { nowEpochMillis }).resolve(
            intent = PlaybackIntent.CatchupPosition(
                channelId = "channel-catchup",
                positionEpochMillis = windowStart + HOUR_MILLIS,
            ),
            liveLocator = LIVE_LOCATOR,
            metadata = supportedMetadata(correction = "+2.0"),
        )

        assertThat(result).isEqualTo(
            M3uCatchupTransportResolution.Unavailable(
                M3uCatchupUnavailableReason.OUTSIDE_RETENTION,
            ),
        )
    }

    @Test
    fun liveIntentNeverMaterializesArchiveTransport() {
        val result = M3uCatchupTransportResolver(nowEpochMillis = { 1_800_000_000_000L }).resolve(
            intent = PlaybackIntent.Live(channelId = "channel-live"),
            liveLocator = LIVE_LOCATOR,
            metadata = supportedMetadata(),
        )

        assertThat(result).isEqualTo(M3uCatchupTransportResolution.NotApplicable)
    }

    private fun supportedMetadata(correction: String = "+2.0") = M3uCatchupMetadata(
        mode = "append",
        source = "?utc={utc}&token=TEST_CATCHUP_SECRET",
        days = 7,
        correction = correction,
    )

    private companion object {
        const val LIVE_LOCATOR =
            "https://streams.invalid/live/catchup.m3u8?token=TEST_CATCHUP_SECRET"
        const val SECOND_MILLIS = 1_000L
        const val HOUR_MILLIS = 60 * 60 * SECOND_MILLIS
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}

package app.muxtv.catalog.refresh

import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uCatchupResolverContractTest {
    @Test
    fun appendUtcProgrammeNormalizesRetentionCorrectionAndGranularity() {
        val nowEpochMillis = 1_800_000_000_000L
        val programmeStart = nowEpochMillis - (2 * HOUR_MILLIS)
        val programmeEnd = nowEpochMillis - HOUR_MILLIS
        val resolver = M3uCatchupResolver(nowEpochMillis = { nowEpochMillis })

        val result = resolver.resolve(
            intent = PlaybackIntent.CatchupProgram(
                channelId = "channel-catchup",
                programmeId = "programme-catchup",
                startEpochMillis = programmeStart,
                endEpochMillis = programmeEnd,
            ),
            metadata = M3uCatchupMetadata(
                mode = "append",
                source = "?utc={utc}&token=TEST_CATCHUP_SECRET",
                days = 7,
                correction = "+2.0",
            ),
        )

        val ready = result as M3uCatchupResolution.Ready
        assertThat(ready.timeline.windowStartEpochMillis)
            .isEqualTo(nowEpochMillis - (7 * DAY_MILLIS))
        assertThat(ready.timeline.windowEndEpochMillis).isEqualTo(nowEpochMillis)
        assertThat(ready.timeline.programmeStartEpochMillis).isEqualTo(programmeStart)
        assertThat(ready.timeline.programmeEndEpochMillis).isEqualTo(programmeEnd)
        assertThat(ready.timeline.initialPositionEpochMillis).isEqualTo(programmeStart)
        assertThat(ready.timeline.correctionMillis).isEqualTo(2 * HOUR_MILLIS)
        assertThat(ready.timeline.granularityMillis).isEqualTo(SECOND_MILLIS)
        assertThat(ready.timeline.playAsLive).isFalse()
    }

    @Test
    fun correctionOutsideDocumentedRangeReturnsInvalidMetadata() {
        val nowEpochMillis = 1_800_000_000_000L
        val intent = PlaybackIntent.CatchupPosition(
            channelId = "channel-catchup",
            positionEpochMillis = nowEpochMillis - HOUR_MILLIS,
        )
        val resolver = M3uCatchupResolver(nowEpochMillis = { nowEpochMillis })

        listOf("-12.1", "+14.1").forEach { correction ->
            val result = resolver.resolve(
                intent = intent,
                metadata = supportedMetadata(correction = correction),
            )

            assertThat(result).isEqualTo(
                M3uCatchupResolution.Unavailable(M3uCatchupUnavailableReason.INVALID_METADATA),
            )
        }
    }

    @Test
    fun liveIntentNeverSelectsArchiveMetadata() {
        val result = M3uCatchupResolver(nowEpochMillis = { 1_800_000_000_000L }).resolve(
            intent = PlaybackIntent.Live(channelId = "channel-live"),
            metadata = supportedMetadata(),
        )

        assertThat(result).isEqualTo(M3uCatchupResolution.NotApplicable)
    }

    @Test
    fun programmeOutsideRetentionReturnsTypedUnavailable() {
        val nowEpochMillis = 1_800_000_000_000L
        val result = M3uCatchupResolver(nowEpochMillis = { nowEpochMillis }).resolve(
            intent = PlaybackIntent.CatchupProgram(
                channelId = "channel-old",
                programmeId = "programme-old",
                startEpochMillis = nowEpochMillis - (8 * DAY_MILLIS),
                endEpochMillis = nowEpochMillis - (8 * DAY_MILLIS) + HOUR_MILLIS,
            ),
            metadata = supportedMetadata(),
        )

        assertThat(result).isEqualTo(
            M3uCatchupResolution.Unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION),
        )
    }

    @Test
    fun unsupportedModeReturnsTypedUnavailableWithoutEchoingSourceTemplate() {
        val metadata = M3uCatchupMetadata(
            mode = "xc",
            source = "?utc={utc}&token=TEST_CATCHUP_SECRET",
            days = 7,
            correction = "+2.0",
        )
        val result = M3uCatchupResolver(nowEpochMillis = { 1_800_000_000_000L }).resolve(
            intent = PlaybackIntent.CatchupPosition(
                channelId = "channel-catchup",
                positionEpochMillis = 1_799_999_000_000L,
            ),
            metadata = metadata,
        )

        assertThat(result).isEqualTo(
            M3uCatchupResolution.Unavailable(M3uCatchupUnavailableReason.UNSUPPORTED_MODE),
        )
        assertThat(metadata.toString()).doesNotContain("TEST_CATCHUP_SECRET")
        assertThat(result.toString()).doesNotContain("TEST_CATCHUP_SECRET")
    }

    private fun supportedMetadata(correction: String = "+2.0") = M3uCatchupMetadata(
        mode = "append",
        source = "?utc={utc}&token=TEST_CATCHUP_SECRET",
        days = 7,
        correction = correction,
    )

    private companion object {
        const val SECOND_MILLIS = 1_000L
        const val HOUR_MILLIS = 60 * 60 * SECOND_MILLIS
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}

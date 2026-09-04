package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveUnavailableReason
import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class XtreamPlaybackArchiveResolverContractTest {
    @Test
    fun `programme intent resolves to opaque minute-granularity Xtream archive reference`() {
        val nowEpochMillis = instant("2026-09-02T12:30:00Z")
        val programmeStart = instant("2026-09-02T10:15:30Z")
        val programmeEnd = instant("2026-09-02T11:16:00Z")
        val transportStart = instant("2026-09-02T10:15:00Z")
        val resolver = XtreamPlaybackArchiveResolver(nowEpochMillis = { nowEpochMillis })

        val result = resolver.resolve(
            PlaybackArchiveRequest(
                intent = PlaybackIntent.CatchupProgram(
                    channelId = "channel-xtream",
                    programmeId = "programme-xtream",
                    startEpochMillis = programmeStart,
                    endEpochMillis = programmeEnd,
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

        val ready = result as PlaybackArchiveResolution.Ready
        assertThat(ready.locator)
            .isEqualTo("muxtv-provider://xtream/archive/707/61/$transportStart/m3u8")
        assertThat(ready.timeline.windowStartEpochMillis)
            .isEqualTo(nowEpochMillis - (7 * DAY_MILLIS))
        assertThat(ready.timeline.windowEndEpochMillis).isEqualTo(nowEpochMillis)
        assertThat(ready.timeline.programmeStartEpochMillis).isEqualTo(programmeStart)
        assertThat(ready.timeline.programmeEndEpochMillis).isEqualTo(programmeEnd)
        assertThat(ready.timeline.initialPositionEpochMillis).isEqualTo(programmeStart)
        assertThat(ready.timeline.correctionMillis).isEqualTo(0L)
        assertThat(ready.timeline.granularityMillis).isEqualTo(MINUTE_MILLIS)
        assertThat(ready.timeline.playAsLive).isFalse()
        assertThat(ready.toString()).doesNotContain("707")
    }

    @Test
    fun `live and non-Xtream requests remain not applicable`() {
        val resolver = XtreamPlaybackArchiveResolver(nowEpochMillis = { instant("2026-09-02T12:30:00Z") })

        val live = resolver.resolve(
            request(
                intent = PlaybackIntent.Live("channel-live"),
                reference = "muxtv-provider://xtream/live/707/ts",
            ),
        )
        val m3u = resolver.resolve(
            request(
                intent = catchupProgram(),
                reference = "https://streams.invalid/live.m3u8",
            ),
        )

        assertThat(live).isEqualTo(PlaybackArchiveResolution.NotApplicable)
        assertThat(m3u).isEqualTo(PlaybackArchiveResolution.NotApplicable)
    }

    @Test
    fun `Xtream archive availability and retention failures are typed`() {
        val nowEpochMillis = instant("2026-09-02T12:30:00Z")
        val resolver = XtreamPlaybackArchiveResolver(nowEpochMillis = { nowEpochMillis })

        val unavailable = resolver.resolve(
            request(
                intent = catchupProgram(),
                reference = "muxtv-provider://xtream/live/707/ts",
                mode = null,
                days = null,
            ),
        )
        val invalidRetention = resolver.resolve(
            request(
                intent = catchupProgram(),
                reference = "muxtv-provider://xtream/live/707/ts",
                mode = "xtream",
                days = 0,
            ),
        )
        val outsideRetention = resolver.resolve(
            request(
                intent = PlaybackIntent.CatchupProgram(
                    channelId = "channel-xtream",
                    programmeId = "programme-old",
                    startEpochMillis = instant("2026-08-30T10:00:00Z"),
                    endEpochMillis = instant("2026-08-30T11:00:00Z"),
                ),
                reference = "muxtv-provider://xtream/live/707/ts",
                mode = "xtream",
                days = 1,
            ),
        )
        val positionUnsupported = resolver.resolve(
            request(
                intent = PlaybackIntent.CatchupPosition(
                    channelId = "channel-xtream",
                    positionEpochMillis = instant("2026-09-02T10:00:00Z"),
                ),
                reference = "muxtv-provider://xtream/live/707/ts",
                mode = "xtream",
                days = 7,
            ),
        )

        assertThat(unavailable).isEqualTo(
            PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.UnsupportedMode),
        )
        assertThat(invalidRetention).isEqualTo(
            PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata),
        )
        assertThat(outsideRetention).isEqualTo(
            PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.OutsideRetention),
        )
        assertThat(positionUnsupported).isEqualTo(
            PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.UnsupportedMode),
        )
    }

    private fun request(
        intent: PlaybackIntent,
        reference: String,
        mode: String? = "xtream",
        days: Int? = 7,
    ): PlaybackArchiveRequest = PlaybackArchiveRequest(
        intent = intent,
        livePlaybackReference = reference,
        metadata = PlaybackArchiveMetadata(
            mode = mode,
            source = null,
            days = days,
            correction = null,
        ),
    )

    private fun catchupProgram(): PlaybackIntent.CatchupProgram = PlaybackIntent.CatchupProgram(
        channelId = "channel-xtream",
        programmeId = "programme-xtream",
        startEpochMillis = instant("2026-09-02T10:00:00Z"),
        endEpochMillis = instant("2026-09-02T11:00:00Z"),
    )

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
        fun instant(value: String): Long = Instant.parse(value).toEpochMilli()
    }
}

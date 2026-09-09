package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.muxtv.player.PlaybackRuntimeHdr
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.player.PlaybackRuntimeVideoCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@AndroidXOptIn(UnstableApi::class)
class PlaybackRuntimeMedia3ProjectionTest {
    @Test
    fun `transport projection preserves the existing classifier decision`() {
        assertThat(PlaybackTransport.HLS.toPlaybackRuntimeTransport())
            .isEqualTo(PlaybackRuntimeTransport.HLS)
        assertThat(PlaybackTransport.MPEG_TS_LIVE.toPlaybackRuntimeTransport())
            .isEqualTo(PlaybackRuntimeTransport.MPEG_TS_LIVE)
        assertThat(PlaybackTransport.DASH.toPlaybackRuntimeTransport())
            .isEqualTo(PlaybackRuntimeTransport.DASH)
        assertThat(PlaybackTransport.PROGRESSIVE.toPlaybackRuntimeTransport())
            .isEqualTo(PlaybackRuntimeTransport.PROGRESSIVE)
        assertThat(PlaybackTransport.AUTO.toPlaybackRuntimeTransport())
            .isEqualTo(PlaybackRuntimeTransport.AUTO)
    }

    @Test
    fun `video format projection uses runtime mime and coarse color evidence only`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(3_840)
            .setHeight(2_160)
            .setAverageBitrate(12_000_000)
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .build(),
            )
            .build()

        val evidence = format.toPlaybackRuntimeVideoFormatEvidence()

        assertThat(evidence.codec).isEqualTo(PlaybackRuntimeVideoCodec.HEVC)
        assertThat(evidence.widthPixels).isEqualTo(3_840)
        assertThat(evidence.heightPixels).isEqualTo(2_160)
        assertThat(evidence.bitrateBitsPerSecond).isEqualTo(12_000_000)
        assertThat(evidence.hdr).isEqualTo(PlaybackRuntimeHdr.PQ)
    }

    @Test
    fun `known SDR and HLG transfers are explicit while unset remains unknown`() {
        val sdr = Format.Builder()
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorTransfer(C.COLOR_TRANSFER_SDR)
                    .build(),
            )
            .build()
            .toPlaybackRuntimeVideoFormatEvidence()
        val hlg = Format.Builder()
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorTransfer(C.COLOR_TRANSFER_HLG)
                    .build(),
            )
            .build()
            .toPlaybackRuntimeVideoFormatEvidence()
        val unset = Format.Builder()
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorTransfer(Format.NO_VALUE)
                    .build(),
            )
            .build()
            .toPlaybackRuntimeVideoFormatEvidence()

        assertThat(sdr.hdr).isEqualTo(PlaybackRuntimeHdr.SDR)
        assertThat(hlg.hdr).isEqualTo(PlaybackRuntimeHdr.HLG)
        assertThat(unset.hdr).isEqualTo(PlaybackRuntimeHdr.UNKNOWN)
    }

    @Test
    fun `dolby vision is not collapsed into a guessed base codec`() {
        val evidence = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setWidth(1_920)
            .setHeight(1_080)
            .build()
            .toPlaybackRuntimeVideoFormatEvidence()

        assertThat(evidence.codec).isEqualTo(PlaybackRuntimeVideoCodec.OTHER)
        assertThat(evidence.hdr).isEqualTo(PlaybackRuntimeHdr.DOLBY_VISION)
    }

    @Test
    fun `unknown format values remain unknown instead of leaking sentinels`() {
        val evidence = Format.Builder().build().toPlaybackRuntimeVideoFormatEvidence()

        assertThat(evidence.codec).isNull()
        assertThat(evidence.widthPixels).isNull()
        assertThat(evidence.heightPixels).isNull()
        assertThat(evidence.bitrateBitsPerSecond).isNull()
        assertThat(evidence.hdr).isEqualTo(PlaybackRuntimeHdr.UNKNOWN)
    }

    @Test
    fun `analytics listener records only events resolved to the active generation`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(9L, PlaybackRuntimeTransport.HLS, null)
        var resolvedGeneration = 9L
        val listener = PlaybackRuntimeAnalyticsListener(
            state = state,
            eventGeneration = { resolvedGeneration },
        )

        listener.onPlaybackStateChanged(eventTime(100L), Player.STATE_BUFFERING)
        listener.onPlaybackStateChanged(eventTime(200L), Player.STATE_READY)
        listener.onPlaybackStateChanged(eventTime(300L), Player.STATE_BUFFERING)
        listener.onPlaybackStateChanged(eventTime(450L), Player.STATE_READY)
        listener.onVideoInputFormatChanged(
            eventTime(460L),
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_AV1)
                .setWidth(1_280)
                .setHeight(720)
                .setAverageBitrate(2_500_000)
                .build(),
            null,
        )

        val accepted = state.snapshot()!!
        assertThat(accepted.rebufferCount).isEqualTo(1)
        assertThat(accepted.completedRebufferDurationMillis).isEqualTo(150L)
        assertThat(accepted.videoCodec).isEqualTo(PlaybackRuntimeVideoCodec.AV1)

        resolvedGeneration = 8L
        listener.onPlaybackStateChanged(eventTime(500L), Player.STATE_BUFFERING)
        listener.onPlaybackStateChanged(eventTime(900L), Player.STATE_READY)
        listener.onVideoInputFormatChanged(
            eventTime(910L),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            null,
        )

        val afterStale = state.snapshot()!!
        assertThat(afterStale.rebufferCount).isEqualTo(1)
        assertThat(afterStale.completedRebufferDurationMillis).isEqualTo(150L)
        assertThat(afterStale.videoCodec).isEqualTo(PlaybackRuntimeVideoCodec.AV1)
    }

    @Test
    fun `decoder analytics callbacks stay generation gated and separate codec errors from drops`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(12L, PlaybackRuntimeTransport.MPEG_TS_LIVE, null)
        var resolvedGeneration = 12L
        val listener = PlaybackRuntimeAnalyticsListener(
            state = state,
            eventGeneration = { resolvedGeneration },
        )

        listener.onVideoDecoderInitialized(
            eventTime = eventTime(100L),
            decoderName = "c2.android.hevc.decoder",
            initializedTimestampMs = 90L,
            initializationDurationMs = 10L,
        )
        listener.onDroppedVideoFrames(eventTime(200L), 4, 100L)
        listener.onVideoCodecError(eventTime(250L), IllegalStateException("diagnostic"))

        val accepted = state.snapshot()!!
        assertThat(accepted.videoDecoderName).isEqualTo("c2.android.hevc.decoder")
        assertThat(accepted.videoDecoderInitializationDurationMillis).isEqualTo(10L)
        assertThat(accepted.droppedVideoFrameCount).isEqualTo(4)
        assertThat(accepted.videoCodecErrorCount).isEqualTo(1)
        assertThat(accepted.decoderInitializationFailureCount).isEqualTo(0)

        resolvedGeneration = 11L
        listener.onVideoDecoderInitialized(eventTime(300L), "decoder.stale", 290L, 99L)
        listener.onDroppedVideoFrames(eventTime(310L), 7, 10L)
        listener.onVideoCodecError(eventTime(320L), IllegalStateException("stale"))

        val afterStale = state.snapshot()!!
        assertThat(afterStale.videoDecoderName).isEqualTo("c2.android.hevc.decoder")
        assertThat(afterStale.droppedVideoFrameCount).isEqualTo(4)
        assertThat(afterStale.videoCodecErrorCount).isEqualTo(1)
    }

    private fun eventTime(realtimeMs: Long): AnalyticsListener.EventTime =
        AnalyticsListener.EventTime(
            realtimeMs,
            Timeline.EMPTY,
            0,
            null,
            0L,
            Timeline.EMPTY,
            0,
            null,
            0L,
            0L,
        )
}

package app.muxtv.player.media3

import app.muxtv.player.DeviceDisplayMode
import app.muxtv.player.DeviceHdrType
import app.muxtv.player.DevicePlaybackProfileSummary
import app.muxtv.player.DeviceVideoCodec
import app.muxtv.player.DeviceVideoDecodeCapability
import app.muxtv.player.HardwareAccelerationEvidence
import app.muxtv.player.PlaybackRuntimeHdr
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.player.PlaybackRuntimeVideoCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRuntimeMeasurementStateTest {
    @Test
    fun `initial buffering is not a rebuffer and completed rebuffer is accumulated once`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(
            generation = 7L,
            transport = PlaybackRuntimeTransport.HLS,
            deviceSummary = null,
        )

        state.onBuffering(generation = 7L, realtimeMs = 100L)
        state.onReady(generation = 7L, realtimeMs = 200L)
        assertThat(state.snapshot()!!.rebufferCount).isEqualTo(0)
        assertThat(state.snapshot()!!.completedRebufferDurationMillis).isEqualTo(0L)

        state.onBuffering(generation = 7L, realtimeMs = 300L)
        state.onBuffering(generation = 7L, realtimeMs = 350L)
        state.onReady(generation = 7L, realtimeMs = 525L)

        assertThat(state.snapshot()!!.rebufferCount).isEqualTo(1)
        assertThat(state.snapshot()!!.completedRebufferDurationMillis).isEqualTo(225L)
    }

    @Test
    fun `incomplete rebuffer is not counted and new generation resets state`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(1L, PlaybackRuntimeTransport.PROGRESSIVE, null)
        state.onReady(1L, 10L)
        state.onBuffering(1L, 20L)

        assertThat(state.snapshot()!!.rebufferCount).isEqualTo(0)
        assertThat(state.snapshot()!!.completedRebufferDurationMillis).isEqualTo(0L)

        state.activate(2L, PlaybackRuntimeTransport.DASH, null)

        assertThat(state.snapshot()!!.transport).isEqualTo(PlaybackRuntimeTransport.DASH)
        assertThat(state.snapshot()!!.rebufferCount).isEqualTo(0)
        assertThat(state.snapshot()!!.completedRebufferDurationMillis).isEqualTo(0L)
        assertThat(state.snapshot()!!.firstFrameLatencyMillis).isNull()
        assertThat(state.snapshot()!!.videoCodec).isNull()
    }

    @Test
    fun `stale generation events cannot mutate active snapshot`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(10L, PlaybackRuntimeTransport.HLS, null)
        state.onReady(10L, 10L)
        state.activate(11L, PlaybackRuntimeTransport.MPEG_TS_LIVE, null)

        state.onReady(10L, 20L)
        state.onBuffering(10L, 30L)
        state.onFirstFrame(10L, 40L)
        state.onVideoFormat(
            10L,
            PlaybackRuntimeVideoFormatEvidence(
                codec = PlaybackRuntimeVideoCodec.HEVC,
                widthPixels = 3_840,
                heightPixels = 2_160,
                bitrateBitsPerSecond = 12_000_000,
                hdr = PlaybackRuntimeHdr.PQ,
            ),
        )

        val snapshot = state.snapshot()!!
        assertThat(snapshot.transport).isEqualTo(PlaybackRuntimeTransport.MPEG_TS_LIVE)
        assertThat(snapshot.rebufferCount).isEqualTo(0)
        assertThat(snapshot.firstFrameLatencyMillis).isNull()
        assertThat(snapshot.videoCodec).isNull()
    }

    @Test
    fun `runtime format and first frame keep latest safe format and first latency only`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(3L, PlaybackRuntimeTransport.AUTO, null)

        state.onVideoFormat(
            3L,
            PlaybackRuntimeVideoFormatEvidence(
                codec = PlaybackRuntimeVideoCodec.AVC,
                widthPixels = 1_920,
                heightPixels = 1_080,
                bitrateBitsPerSecond = 6_000_000,
                hdr = PlaybackRuntimeHdr.SDR,
            ),
        )
        state.onFirstFrame(3L, 125L)
        state.onFirstFrame(3L, 999L)

        val snapshot = state.snapshot()!!
        assertThat(snapshot.videoCodec).isEqualTo(PlaybackRuntimeVideoCodec.AVC)
        assertThat(snapshot.videoWidthPixels).isEqualTo(1_920)
        assertThat(snapshot.videoHeightPixels).isEqualTo(1_080)
        assertThat(snapshot.videoBitrateBitsPerSecond).isEqualTo(6_000_000)
        assertThat(snapshot.hdr).isEqualTo(PlaybackRuntimeHdr.SDR)
        assertThat(snapshot.firstFrameLatencyMillis).isEqualTo(125L)
    }

    @Test
    fun `device summary is copied as scalar evidence and clear removes current session`() {
        val state = PlaybackRuntimeMeasurementState()
        val summary = DevicePlaybackProfileSummary(
            videoDecoders = listOf(
                DeviceVideoDecodeCapability(
                    codec = DeviceVideoCodec.HEVC,
                    hardwareAcceleration = HardwareAccelerationEvidence.PRESENT,
                ),
            ),
            currentDisplayMode = DeviceDisplayMode(1_920, 1_080, 60_000),
            supportedDisplayModeCount = 1,
            hdrTypes = setOf(DeviceHdrType.HDR10),
            lowRamDevice = true,
            memoryClassMb = 256,
        )

        state.activate(5L, PlaybackRuntimeTransport.HLS, summary)
        assertThat(state.snapshot()!!.lowRamDevice).isTrue()
        assertThat(state.snapshot()!!.memoryClassMb).isEqualTo(256)

        state.clear()
        assertThat(state.snapshot()).isNull()
    }
}

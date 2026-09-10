package app.muxtv.player.media3

import app.muxtv.player.PlaybackRuntimeTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRuntimeDecoderMeasurementStateTest {
    @Test
    fun `active generation records decoder init codec errors and dropped frames separately`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(7L, PlaybackRuntimeTransport.HLS, null)

        state.onVideoDecoderInitialized(
            generation = 7L,
            decoderName = "c2.android.avc.decoder",
            initializationDurationMillis = 13L,
        )
        state.onDecoderInitializationFailure(7L)
        state.onVideoCodecError(7L)
        state.onDroppedVideoFrames(7L, 2)
        state.onDroppedVideoFrames(7L, 3)

        val snapshot = state.snapshot()!!
        assertThat(snapshot.videoDecoderName).isEqualTo("c2.android.avc.decoder")
        assertThat(snapshot.videoDecoderInitializationDurationMillis).isEqualTo(13L)
        assertThat(snapshot.decoderInitializationFailureCount).isEqualTo(1)
        assertThat(snapshot.videoCodecErrorCount).isEqualTo(1)
        assertThat(snapshot.droppedVideoFrameCount).isEqualTo(5)
    }

    @Test
    fun `stale decoder callbacks fail shut after generation change`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(10L, PlaybackRuntimeTransport.HLS, null)
        state.onVideoDecoderInitialized(10L, "decoder.active", 9L)
        state.activate(11L, PlaybackRuntimeTransport.MPEG_TS_LIVE, null)

        state.onVideoDecoderInitialized(10L, "decoder.stale", 99L)
        state.onDecoderInitializationFailure(10L)
        state.onVideoCodecError(10L)
        state.onDroppedVideoFrames(10L, 7)

        val snapshot = state.snapshot()!!
        assertThat(snapshot.videoDecoderName).isNull()
        assertThat(snapshot.videoDecoderInitializationDurationMillis).isNull()
        assertThat(snapshot.decoderInitializationFailureCount).isEqualTo(0)
        assertThat(snapshot.videoCodecErrorCount).isEqualTo(0)
        assertThat(snapshot.droppedVideoFrameCount).isEqualTo(0)
    }

    @Test
    fun `new generation resets decoder observations`() {
        val state = PlaybackRuntimeMeasurementState()
        state.activate(20L, PlaybackRuntimeTransport.HLS, null)
        state.onVideoDecoderInitialized(20L, "decoder.old", 11L)
        state.onDecoderInitializationFailure(20L)
        state.onVideoCodecError(20L)
        state.onDroppedVideoFrames(20L, 4)

        state.activate(21L, PlaybackRuntimeTransport.DASH, null)

        val snapshot = state.snapshot()!!
        assertThat(snapshot.videoDecoderName).isNull()
        assertThat(snapshot.videoDecoderInitializationDurationMillis).isNull()
        assertThat(snapshot.decoderInitializationFailureCount).isEqualTo(0)
        assertThat(snapshot.videoCodecErrorCount).isEqualTo(0)
        assertThat(snapshot.droppedVideoFrameCount).isEqualTo(0)
    }
}

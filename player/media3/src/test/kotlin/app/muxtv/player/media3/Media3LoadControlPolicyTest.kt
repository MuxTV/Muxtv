package app.muxtv.player.media3

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3LoadControlPolicyTest {
    @Test
    fun `production remains exact current variant`() {
        assertThat(PRODUCTION_MEDIA3_LOAD_CONTROL_VARIANT)
            .isEqualTo(Media3LoadControlVariant.A_CURRENT)
    }

    @Test
    fun `A records exact Media3 1_11 streaming defaults`() {
        val settings = media3LoadControlSettings(Media3LoadControlVariant.A_CURRENT)

        assertThat(settings).isEqualTo(
            Media3LoadControlSettings(
                minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                bufferForPlaybackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                bufferForPlaybackAfterRebufferMs =
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                targetBufferBytes = DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES,
                prioritizeTimeOverSizeThresholds =
                    DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
                backBufferDurationMs = DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
                retainBackBufferFromKeyframe =
                    DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
            ),
        )
        assertThat(settings.minBufferMs).isEqualTo(50_000)
        assertThat(settings.maxBufferMs).isEqualTo(50_000)
        assertThat(settings.bufferForPlaybackMs).isEqualTo(1_000)
        assertThat(settings.bufferForPlaybackAfterRebufferMs).isEqualTo(2_000)
        assertThat(settings.targetBufferBytes).isEqualTo(C.LENGTH_UNSET)
        assertThat(settings.prioritizeTimeOverSizeThresholds).isFalse()
        assertThat(settings.backBufferDurationMs).isEqualTo(0)
        assertThat(settings.retainBackBufferFromKeyframe).isFalse()
    }

    @Test
    fun `B changes only streaming initial playback threshold from A`() {
        val a = media3LoadControlSettings(Media3LoadControlVariant.A_CURRENT)
        val b = media3LoadControlSettings(Media3LoadControlVariant.B_LOWER_START_500MS)

        assertThat(b).isEqualTo(a.copy(bufferForPlaybackMs = 500))
    }

    @Test
    fun `C differs from B by exactly one post rebuffer threshold hypothesis`() {
        val b = media3LoadControlSettings(Media3LoadControlVariant.B_LOWER_START_500MS)
        val c = media3LoadControlSettings(
            Media3LoadControlVariant.C_LOWER_START_500MS_REBUFFER_3000MS,
        )

        assertThat(c).isEqualTo(b.copy(bufferForPlaybackAfterRebufferMs = 3_000))
    }

    @Test
    fun `all variants preserve current byte time and back buffer behavior`() {
        Media3LoadControlVariant.entries.forEach { variant ->
            val settings = media3LoadControlSettings(variant)
            assertThat(settings.targetBufferBytes)
                .isEqualTo(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            assertThat(settings.prioritizeTimeOverSizeThresholds)
                .isEqualTo(DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS)
            assertThat(settings.backBufferDurationMs)
                .isEqualTo(DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS)
            assertThat(settings.retainBackBufferFromKeyframe)
                .isEqualTo(DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME)
        }
    }
}
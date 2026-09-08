package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3RendererPolicyTest {
    @Test
    fun `A preserves current Media3 renderer defaults`() {
        val settings = media3RendererSettings(Media3RendererVariant.A_CURRENT)

        assertThat(settings.enableDecoderFallback).isFalse()
        assertThat(settings.forceSynchronousMediaCodecQueueing).isFalse()
    }

    @Test
    fun `B enables decoder fallback only`() {
        val settings = media3RendererSettings(Media3RendererVariant.B_DECODER_FALLBACK)

        assertThat(settings.enableDecoderFallback).isTrue()
        assertThat(settings.forceSynchronousMediaCodecQueueing).isFalse()
    }

    @Test
    fun `C enables decoder fallback and forces synchronous MediaCodec queueing`() {
        val settings = media3RendererSettings(Media3RendererVariant.C_DECODER_FALLBACK_SYNC_QUEUEING)

        assertThat(settings.enableDecoderFallback).isTrue()
        assertThat(settings.forceSynchronousMediaCodecQueueing).isTrue()
    }

    @Test
    fun `production default remains A before evidence disposition`() {
        assertThat(PRODUCTION_MEDIA3_RENDERER_VARIANT)
            .isEqualTo(Media3RendererVariant.A_CURRENT)
    }
}

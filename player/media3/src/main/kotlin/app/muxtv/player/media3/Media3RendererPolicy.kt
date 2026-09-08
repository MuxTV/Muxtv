package app.muxtv.player.media3

import android.content.Context
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * C09 experimental renderer variants.
 *
 * This seam changes only renderer/codec construction for the existing service-owned player. It
 * does not own a player, alter recovery, tune buffering, add a watchdog, or change seek authority.
 */
internal enum class Media3RendererVariant {
    A_CURRENT,
    B_DECODER_FALLBACK,
    C_DECODER_FALLBACK_SYNC_QUEUEING,
}

internal data class Media3RendererSettings(
    val enableDecoderFallback: Boolean,
    val forceSynchronousMediaCodecQueueing: Boolean,
)

/** Production stays on A until C09 correctness-first evidence records a disposition. */
internal val PRODUCTION_MEDIA3_RENDERER_VARIANT = Media3RendererVariant.A_CURRENT

internal fun media3RendererSettings(variant: Media3RendererVariant): Media3RendererSettings =
    when (variant) {
        Media3RendererVariant.A_CURRENT -> Media3RendererSettings(
            enableDecoderFallback = false,
            forceSynchronousMediaCodecQueueing = false,
        )

        Media3RendererVariant.B_DECODER_FALLBACK -> Media3RendererSettings(
            enableDecoderFallback = true,
            forceSynchronousMediaCodecQueueing = false,
        )

        Media3RendererVariant.C_DECODER_FALLBACK_SYNC_QUEUEING -> Media3RendererSettings(
            enableDecoderFallback = true,
            forceSynchronousMediaCodecQueueing = true,
        )
    }

@AndroidXOptIn(UnstableApi::class)
internal fun createMedia3RenderersFactory(
    context: Context,
    variant: Media3RendererVariant = PRODUCTION_MEDIA3_RENDERER_VARIANT,
): DefaultRenderersFactory {
    val settings = media3RendererSettings(variant)
    return DefaultRenderersFactory(context)
        .setEnableDecoderFallback(settings.enableDecoderFallback)
        .also { factory ->
            if (settings.forceSynchronousMediaCodecQueueing) {
                factory.forceDisableMediaCodecAsynchronousQueueing()
            }
        }
}

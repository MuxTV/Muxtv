package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

internal enum class Media3LoadControlVariant {
    A_CURRENT,
    B_LOWER_START_500MS,
    C_LOWER_START_500MS_REBUFFER_3000MS,
}

internal data class Media3LoadControlSettings(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
    val backBufferDurationMs: Int,
    val retainBackBufferFromKeyframe: Boolean,
)

internal val PRODUCTION_MEDIA3_LOAD_CONTROL_VARIANT = Media3LoadControlVariant.A_CURRENT

@AndroidXOptIn(UnstableApi::class)
internal fun media3LoadControlSettings(
    variant: Media3LoadControlVariant,
): Media3LoadControlSettings {
    val current = Media3LoadControlSettings(
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
    )

    return when (variant) {
        Media3LoadControlVariant.A_CURRENT -> current
        Media3LoadControlVariant.B_LOWER_START_500MS ->
            current.copy(bufferForPlaybackMs = 500)
        Media3LoadControlVariant.C_LOWER_START_500MS_REBUFFER_3000MS ->
            current.copy(
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 3_000,
            )
    }
}

/**
 * Builds the evidence-only LoadControl for a preregistered C10 variant.
 *
 * Only the streaming duration thresholds are written. Every other setting intentionally remains on
 * the Media3 1.11.0 builder default captured by [media3LoadControlSettings]. Production playback
 * does not call this factory while C10 is evidence-only, so the service keeps its current implicit
 * Media3 default construction until an evidence disposition explicitly changes that policy.
 */
@AndroidXOptIn(UnstableApi::class)
internal fun createMedia3LoadControl(
    variant: Media3LoadControlVariant,
): DefaultLoadControl {
    val settings = media3LoadControlSettings(variant)
    return DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            settings.minBufferMs,
            settings.maxBufferMs,
            settings.bufferForPlaybackMs,
            settings.bufferForPlaybackAfterRebufferMs,
        )
        .build()
}

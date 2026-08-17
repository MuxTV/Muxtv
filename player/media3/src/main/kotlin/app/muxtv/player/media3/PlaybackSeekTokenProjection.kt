package app.muxtv.player.media3

import android.os.Bundle
import androidx.media3.common.Player

/**
 * Safe process-local projection of the currently installed seek generation.
 *
 * The value is intentionally opaque, non-persistent and contains no locator/provider data.
 */
internal const val PLAYBACK_SEEK_GENERATION_EXTRA =
    "app.muxtv.player.media3.extra.SEEK_GENERATION"

internal fun playbackSeekMetadataExtras(generation: Long): Bundle {
    require(generation > 0L)
    return Bundle().apply {
        putLong(PLAYBACK_SEEK_GENERATION_EXTRA, generation)
    }
}

fun Player.currentPlaybackSeekToken(): PlaybackSeekToken? {
    val mediaItem = currentMediaItem ?: return null
    val extras = mediaItem.mediaMetadata.extras ?: return null
    val generation = extras.getLong(PLAYBACK_SEEK_GENERATION_EXTRA, 0L)
    if (generation <= 0L) return null
    return runCatching {
        PlaybackSeekToken(
            mediaId = mediaItem.mediaId,
            generation = generation,
        )
    }.getOrNull()
}

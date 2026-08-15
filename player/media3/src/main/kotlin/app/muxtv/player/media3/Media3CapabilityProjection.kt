package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.muxtv.player.PlayerCapabilities

/**
 * Pure capability derivation from Media3 player state. Host-unit-testable: no Context, no
 * controller, no listener wiring. Compose collection is a thin wrapper around this function.
 */
@AndroidXOptIn(UnstableApi::class)
fun derivePlayerCapabilities(
    availableCommands: Set<Int>,
    tracks: Tracks?,
    durationMs: Long,
    isLive: Boolean,
    favoriteSupported: Boolean,
): PlayerCapabilities = PlayerCapabilities(
    canSeek = Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in availableCommands,
    canPause = Player.COMMAND_PLAY_PAUSE in availableCommands,
    canSetTrackSelection =
        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS in availableCommands,
    hasAudioTracks = tracks.orEmpty().containsType(C.TRACK_TYPE_AUDIO),
    hasTextTracks = tracks.orEmpty().containsType(C.TRACK_TYPE_TEXT),
    supportsFavorite = favoriteSupported,
    hasKnownDuration = durationMs != C.TIME_UNSET && durationMs > 0L,
    isLive = isLive,
)

private fun Tracks?.orEmpty(): Tracks = this ?: Tracks.EMPTY

/** [Player.Commands] does not implement [Set]; converts it for capability derivation. */
fun Player.Commands.toIntSet(): Set<Int> {
    val result = HashSet<Int>(size())
    for (index in 0 until size()) {
        result.add(get(index))
    }
    return result
}

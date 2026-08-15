package app.muxtv.player

/**
 * Capability projection of the current playback session for presentation decisions.
 *
 * Derived exclusively from controller/player state (available commands, prepared tracks,
 * timeline, media configuration). The UI never derives capabilities from the word
 * "TorrServer", the route kind or a persisted flag: capability means what Media3 currently
 * supports for the installed media item.
 */
data class PlayerCapabilities(
    val canSeek: Boolean,
    val canPause: Boolean,
    val canSetTrackSelection: Boolean,
    val hasAudioTracks: Boolean,
    val hasTextTracks: Boolean,
    val supportsFavorite: Boolean,
    val hasKnownDuration: Boolean,
    val isLive: Boolean,
)

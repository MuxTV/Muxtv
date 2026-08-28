package app.muxtv.player

/**
 * Capability projection of the current playback session for presentation decisions.
 *
 * Derived exclusively from the active playback implementation state: available commands,
 * prepared tracks and timeline/media state. Presentation must not infer capability from source
 * names, route kinds or persisted flags.
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

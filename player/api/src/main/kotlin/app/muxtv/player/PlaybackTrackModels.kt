package app.muxtv.player

/**
 * Session-local key of an individual track inside the current playback snapshot. The key is
 * derived by the playback implementation from stable group identity (or a positional fallback)
 * plus the track index; the human-readable label is never identity.
 */
data class TrackKey(
    val groupId: String,
    val trackIndex: Int,
) {
    init {
        require(groupId.isValidKeyPart())
        require(trackIndex >= 0)
    }
}

/** Immutable presentation projection of one audio track. No engine objects cross this boundary. */
data class AudioTrackUiModel(
    val key: TrackKey,
    val selected: Boolean,
    val supported: Boolean,
    val primaryLabel: String,
    val languageLabel: String?,
    val technicalLabel: String,
    val isDefault: Boolean,
    val isForced: Boolean,
) {
    init {
        require(primaryLabel.isValidTrackText())
        require(languageLabel == null || languageLabel.isValidTrackText())
        require(technicalLabel.isValidTrackText())
    }
}

/** Immutable presentation projection of one embedded text track. */
data class SubtitleTrackUiModel(
    val key: TrackKey,
    val selected: Boolean,
    val supported: Boolean,
    val primaryLabel: String,
    val languageLabel: String?,
    val technicalLabel: String,
    val isForced: Boolean,
) {
    init {
        require(primaryLabel.isValidTrackText())
        require(languageLabel == null || languageLabel.isValidTrackText())
        require(technicalLabel.isValidTrackText())
    }
}

private fun String.isValidKeyPart(): Boolean =
    isNotBlank() && length <= MAX_KEY_LENGTH && !contains('\r') && !contains('\n')

private fun String.isValidTrackText(): Boolean =
    isNotBlank() && length <= MAX_TRACK_TEXT_LENGTH && !contains('\r') && !contains('\n')

private const val MAX_KEY_LENGTH = 512
private const val MAX_TRACK_TEXT_LENGTH = 512

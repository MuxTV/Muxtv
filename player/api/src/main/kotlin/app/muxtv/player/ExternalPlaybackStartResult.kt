package app.muxtv.player

sealed interface ExternalPlaybackStartResult {
    data object Started : ExternalPlaybackStartResult

    data class Rejected(
        val reason: ExternalPlaybackStartFailure,
        val observationAvailable: Boolean = false,
    ) : ExternalPlaybackStartResult
}

enum class ExternalPlaybackStartFailure {
    InvalidDescriptor,
    CleartextNotApproved,
    LeaseUnavailable,
    PlaybackFailed,
}

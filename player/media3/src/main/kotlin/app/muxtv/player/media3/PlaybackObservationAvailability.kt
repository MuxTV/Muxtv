package app.muxtv.player.media3

internal fun hasPlaybackAttemptEvidence(
    failure: PlaybackRecoveryFailure,
    attemptNumber: Int,
): Boolean = when (failure) {
    PlaybackRecoveryFailure.CandidatesExhausted -> attemptNumber > 0
    PlaybackRecoveryFailure.DeadlineExceeded -> attemptNumber > 0
    PlaybackRecoveryFailure.NoCandidates,
    PlaybackRecoveryFailure.AccessUnavailable,
    -> false
}

package app.muxtv.player.media3

internal fun hasPlaybackAttemptEvidence(
    failure: PlaybackRecoveryFailure,
    attemptNumber: Int,
): Boolean = when (failure) {
    PlaybackRecoveryFailure.CandidatesExhausted,
    PlaybackRecoveryFailure.DeadlineExceeded,
    PlaybackRecoveryFailure.TerminalFailure,
    -> attemptNumber > 0
    PlaybackRecoveryFailure.NoCandidates,
    PlaybackRecoveryFailure.AccessUnavailable,
    -> false
}

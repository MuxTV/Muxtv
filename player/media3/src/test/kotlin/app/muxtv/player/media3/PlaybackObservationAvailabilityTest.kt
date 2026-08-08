package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackObservationAvailabilityTest {
    @Test
    fun `source and credential failures do not claim playback evidence`() {
        assertThat(
            hasPlaybackAttemptEvidence(PlaybackRecoveryFailure.NoCandidates, attemptNumber = 0),
        ).isFalse()
        assertThat(
            hasPlaybackAttemptEvidence(PlaybackRecoveryFailure.AccessUnavailable, attemptNumber = 1),
        ).isFalse()
    }

    @Test
    fun `attempt exhaustion and post-attempt deadline claim playback evidence`() {
        assertThat(
            hasPlaybackAttemptEvidence(PlaybackRecoveryFailure.CandidatesExhausted, attemptNumber = 1),
        ).isTrue()
        assertThat(
            hasPlaybackAttemptEvidence(PlaybackRecoveryFailure.DeadlineExceeded, attemptNumber = 1),
        ).isTrue()
        assertThat(
            hasPlaybackAttemptEvidence(PlaybackRecoveryFailure.DeadlineExceeded, attemptNumber = 0),
        ).isFalse()
    }
}

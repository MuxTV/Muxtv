package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity

internal data class PlaybackAttemptToken(
    val setupId: PlaybackSetupId,
    val generation: Long,
    val candidate: PlaybackCandidateIdentity,
    val attempt: Int = 0,
) {
    fun matches(
        setupId: PlaybackSetupId?,
        generation: Long?,
        candidate: PlaybackCandidateIdentity?,
    ): Boolean =
        this.setupId == setupId &&
            this.generation == generation &&
            this.candidate == candidate
}

internal class PlaybackCallbackGate {
    private var current: PlaybackAttemptToken? = null

    fun activate(token: PlaybackAttemptToken) {
        current = token
    }

    fun isCurrent(token: PlaybackAttemptToken): Boolean = current == token

    fun consume(token: PlaybackAttemptToken): Boolean {
        if (current != token) return false
        current = null
        return true
    }

    fun clear() {
        current = null
    }
}

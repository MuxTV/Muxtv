package app.muxtv.player

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackSessionPhase {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

data class PlaybackSessionState(
    val channelId: String?,
    val phase: PlaybackSessionPhase,
    val isPlaying: Boolean,
) {
    init {
        require(channelId == null || channelId.isNotBlank())
        if (phase == PlaybackSessionPhase.IDLE) {
            require(channelId == null)
        } else {
            require(channelId != null)
        }
        if (isPlaying) {
            require(phase == PlaybackSessionPhase.READY)
        }
    }

    val hasActiveChannel: Boolean
        get() = channelId != null

    override fun toString(): String =
        "PlaybackSessionState(channelPresent=${channelId != null}, phase=$phase, isPlaying=$isPlaying)"

    companion object {
        val Idle = PlaybackSessionState(
            channelId = null,
            phase = PlaybackSessionPhase.IDLE,
            isPlaying = false,
        )
    }
}

interface PlaybackSessionStateSource {
    val playbackSessionState: StateFlow<PlaybackSessionState>
}

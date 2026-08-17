package app.muxtv.player.media3

/** Opaque process-local identity for exactly one installed playback generation. */
data class PlaybackSeekToken(
    val mediaId: String,
    val generation: Long,
) {
    init {
        require(mediaId.isNotBlank())
        require(!mediaId.contains('\r') && !mediaId.contains('\n'))
        require(generation > 0L)
    }
}

/** Semantic request accepted by the single service-owned seek authority. */
sealed interface PlaybackSeekRequest {
    val token: PlaybackSeekToken

    data class Relative(
        override val token: PlaybackSeekToken,
        val direction: Int,
    ) : PlaybackSeekRequest {
        init {
            require(
                direction == PlaybackSeekPolicy.DIRECTION_BACKWARD ||
                    direction == PlaybackSeekPolicy.DIRECTION_FORWARD,
            )
        }
    }

    data class Absolute(
        override val token: PlaybackSeekToken,
        val targetMs: Long,
    ) : PlaybackSeekRequest {
        init {
            require(targetMs >= 0L)
        }
    }
}

enum class PlaybackSeekRejectReason {
    STALE_PLAYBACK,
    COMMAND_UNAVAILABLE,
    LIVE_CONTENT,
    UNKNOWN_DURATION,
    INVALID_POSITION,
    INVALID_TARGET,
    CONTROLLER_REJECTED,
}

/** Policy result. Media3/Binder transport success or failure is a separate concern. */
sealed interface PlaybackSeekResult {
    data class Accepted(
        val targetMs: Long,
        val direction: Int,
    ) : PlaybackSeekResult {
        init {
            require(targetMs >= 0L)
            require(direction in PlaybackSeekPolicy.DIRECTION_BACKWARD..PlaybackSeekPolicy.DIRECTION_FORWARD)
        }
    }

    data class Rejected(
        val reason: PlaybackSeekRejectReason,
    ) : PlaybackSeekResult
}

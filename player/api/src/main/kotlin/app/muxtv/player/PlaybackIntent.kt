package app.muxtv.player

/**
 * Provider-neutral semantic playback request before any provider transport is materialized.
 *
 * Provider URLs/templates and credentials must never enter this contract. Live and archive
 * requests are resolved later by the catalog/provider boundary into the existing transport path.
 */
sealed interface PlaybackIntent {
    val channelId: String

    data class Live(
        override val channelId: String,
    ) : PlaybackIntent {
        init {
            require(channelId.isValidPlaybackIdentity())
        }

        override fun toString(): String = "PlaybackIntent.Live(channelId=<redacted>)"
    }

    data class CatchupProgram(
        override val channelId: String,
        val programmeId: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : PlaybackIntent {
        init {
            require(channelId.isValidPlaybackIdentity())
            require(programmeId.isValidPlaybackIdentity())
            require(startEpochMillis < endEpochMillis)
        }

        override fun toString(): String =
            "PlaybackIntent.CatchupProgram(channelId=<redacted>, programmeId=<redacted>, " +
                "startEpochMillis=$startEpochMillis, endEpochMillis=$endEpochMillis)"
    }

    data class CatchupPosition(
        override val channelId: String,
        val positionEpochMillis: Long,
    ) : PlaybackIntent {
        init {
            require(channelId.isValidPlaybackIdentity())
        }

        override fun toString(): String =
            "PlaybackIntent.CatchupPosition(channelId=<redacted>, " +
                "positionEpochMillis=$positionEpochMillis)"
    }
}

/**
 * Provider-neutral, already-normalized archive timeline semantics.
 *
 * All timestamps are absolute UTC epoch milliseconds. Provider-specific URL templates, time-zone
 * syntax and credential material are resolved before/after this boundary as appropriate; this
 * value contains only deterministic playback-time semantics.
 */
data class ResolvedPlaybackTimeline(
    val windowStartEpochMillis: Long,
    val windowEndEpochMillis: Long,
    val programmeStartEpochMillis: Long?,
    val programmeEndEpochMillis: Long?,
    val initialPositionEpochMillis: Long,
    val correctionMillis: Long,
    val granularityMillis: Long?,
    val playAsLive: Boolean,
) {
    init {
        require(windowStartEpochMillis < windowEndEpochMillis)
        require((programmeStartEpochMillis == null) == (programmeEndEpochMillis == null))

        if (programmeStartEpochMillis != null && programmeEndEpochMillis != null) {
            require(programmeStartEpochMillis < programmeEndEpochMillis)
            require(programmeStartEpochMillis >= windowStartEpochMillis)
            require(programmeEndEpochMillis <= windowEndEpochMillis)
        }

        require(initialPositionEpochMillis >= windowStartEpochMillis)
        require(initialPositionEpochMillis < windowEndEpochMillis)
        require(granularityMillis == null || granularityMillis > 0)
    }
}

private fun String.isValidPlaybackIdentity(): Boolean =
    isNotBlank() && length <= MAX_PLAYBACK_IDENTITY_LENGTH && !contains('\r') && !contains('\n')

private const val MAX_PLAYBACK_IDENTITY_LENGTH = 512

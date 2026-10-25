package app.muxtv.network

enum class MuxTvNetworkClientKind {
    SOURCE,
    PLAYBACK,
}

enum class MuxTvNetworkPhase {
    DNS,
    CONNECT,
    TLS,
    CONNECTION_ACQUIRE,
    REQUEST,
    TTFB,
    RESPONSE_BODY,
    TOTAL,
}

enum class MuxTvNetworkOutcome {
    SUCCEEDED,
    FAILED,
}

data class MuxTvNetworkTimingObservation(
    val clientKind: MuxTvNetworkClientKind,
    val phase: MuxTvNetworkPhase,
    val outcome: MuxTvNetworkOutcome,
    val durationNanos: Long?,
) {
    init {
        require(durationNanos == null || durationNanos >= 0L) {
            "durationNanos must be non-negative when present"
        }
    }
}

internal fun muxTvNetworkDurationNanos(
    startNanos: Long,
    endNanos: Long,
): Long = (endNanos - startNanos).coerceAtLeast(0L)

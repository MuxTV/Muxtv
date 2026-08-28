package app.muxtv.player

import kotlinx.coroutines.flow.StateFlow

/** Engine-neutral failure surface for connecting to or commanding the active playback session. */
enum class PlaybackSessionOperationFailure {
    ConnectorClosed,
    ConnectionTimedOut,
    ConnectionCancelled,
    ConnectionFailed,
    CommandTimedOut,
    CommandCancelled,
    CommandFailed,
}

class PlaybackSessionOperationException(
    val failure: PlaybackSessionOperationFailure,
) : Exception("Playback session operation failed: $failure")

/** Engine-neutral lifecycle phase exposed to presentation. */
enum class PlaybackSessionPhase {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

enum class PlaybackSeekDirection(val sign: Int) {
    BACKWARD(-1),
    FORWARD(1),
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

sealed interface PlaybackSeekResult {
    data class Accepted(
        val targetMs: Long,
        val direction: PlaybackSeekDirection,
    ) : PlaybackSeekResult {
        init {
            require(targetMs >= 0L)
        }
    }

    data class Rejected(
        val reason: PlaybackSeekRejectReason,
    ) : PlaybackSeekResult
}

data class PlaybackTimelineState(
    val positionMs: Long,
    val durationMs: Long?,
    val isLive: Boolean,
) {
    init {
        require(positionMs >= 0L)
        require(durationMs == null || durationMs > 0L)
    }

    val hasKnownDuration: Boolean
        get() = durationMs != null
}

/**
 * Immutable state consumed by presentation. Implementation-specific player/controller objects,
 * command identifiers and transport tokens never cross this boundary.
 */
data class PlaybackSessionSnapshot(
    val phase: PlaybackSessionPhase,
    val isPlaying: Boolean,
    val hasError: Boolean,
    val capabilities: PlayerCapabilities,
    val timeline: PlaybackTimelineState,
    val audioTracks: List<AudioTrackUiModel>,
    val subtitleTracks: List<SubtitleTrackUiModel>,
    val subtitlesDisabled: Boolean,
) {
    init {
        require(!isPlaying || phase == PlaybackSessionPhase.READY)
    }
}

/**
 * Stable control surface for one connected playback session.
 *
 * Implementations retain engine generation identity and transport details internally. In
 * particular, relative seek is semantic here; stale-generation validation and coalescing remain
 * owned by the playback implementation/service.
 *
 * Session implementations may own transient observation/listener resources. [close] releases
 * only those adapter resources; it does not transfer or destroy the process-owned playback
 * engine/service.
 */
interface PlaybackControlSession : AutoCloseable {
    val state: StateFlow<PlaybackSessionSnapshot>

    suspend fun start(
        request: PlaybackStartRequest,
        timeoutMillis: Long,
    ): PlaybackStartResult

    fun play()
    fun pause()
    fun stop()

    /**
     * Samples the active engine timeline without exposing engine objects or sentinel values.
     * Presentation uses this only for bounded timeline refresh while its controls are visible.
     */
    suspend fun currentTimeline(): PlaybackTimelineState

    suspend fun seekRelative(
        direction: PlaybackSeekDirection,
        timeoutMillis: Long,
    ): PlaybackSeekResult

    fun selectAudioTrack(key: TrackKey)
    fun selectSubtitleTrack(key: TrackKey)
    fun disableSubtitles()

    override fun close() = Unit
}

/** Stable connector consumed by presentation instead of an engine-specific controller connector. */
interface PlaybackSessionGateway {
    val connectionEpoch: StateFlow<Long>

    suspend fun awaitSession(timeoutMillis: Long): PlaybackControlSession
}

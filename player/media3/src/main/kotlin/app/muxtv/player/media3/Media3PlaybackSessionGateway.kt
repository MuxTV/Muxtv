package app.muxtv.player.media3

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.muxtv.player.PlaybackControlSession
import app.muxtv.player.PlaybackSeekDirection
import app.muxtv.player.PlaybackSeekRejectReason as StableSeekRejectReason
import app.muxtv.player.PlaybackSeekResult as StableSeekResult
import app.muxtv.player.PlaybackSessionGateway
import app.muxtv.player.PlaybackSessionOperationException
import app.muxtv.player.PlaybackSessionOperationFailure
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionSnapshot
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.PlaybackTimelineState
import app.muxtv.player.TrackKey
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Media3 implementation of the stable playback-session boundary.
 *
 * Presentation receives only [PlaybackControlSession]. MediaController, command ids, seek
 * generation tokens and Media3 track objects remain private to this module.
 */
class Media3PlaybackSessionGateway(
    private val connector: MuxTvMediaControllerConnector,
) : PlaybackSessionGateway {
    override val connectionEpoch: StateFlow<Long>
        get() = connector.connectionEpoch

    override suspend fun awaitSession(timeoutMillis: Long): PlaybackControlSession {
        val controller = try {
            connector.awaitController(timeoutMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MediaControllerOperationException) {
            throw PlaybackSessionOperationException(error.failure.toStableFailure())
        } catch (_: Exception) {
            throw PlaybackSessionOperationException(PlaybackSessionOperationFailure.ConnectionFailed)
        }
        return sessionFor(controller)
    }

    /**
     * Wraps an already-connected controller without changing playback ownership. This is used by
     * the external playback flow, which must attach a video surface before awaiting first-frame
     * completion from the service.
     */
    fun sessionFor(controller: MediaController): PlaybackControlSession =
        Media3PlaybackControlSession(connector, controller)
}

internal class Media3PlaybackControlSession(
    private val connector: MuxTvMediaControllerConnector,
    internal val controller: MediaController,
) : PlaybackControlSession {
    private val projector = Media3TrackProjector()
    private val mutableState = MutableStateFlow(emptySnapshot())
    override val state: StateFlow<PlaybackSessionSnapshot> = mutableState
    private var closed = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishSnapshot(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            publishSnapshot(controller)
        }
    }

    init {
        controller.runOnPlaybackApplicationThread {
            if (!closed) {
                controller.addListener(listener)
                publishSnapshot(controller)
            }
        }
    }

    override suspend fun start(
        request: PlaybackStartRequest,
        timeoutMillis: Long,
    ): PlaybackStartResult = try {
        connector.awaitPlaybackStart(
            controller = controller,
            request = request,
            timeoutMillis = timeoutMillis,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: MediaControllerOperationException) {
        throw PlaybackSessionOperationException(error.failure.toStableFailure())
    } catch (_: Exception) {
        throw PlaybackSessionOperationException(PlaybackSessionOperationFailure.CommandFailed)
    }

    override fun play() = controller.runOnPlaybackApplicationThread { controller.play() }

    override fun pause() = controller.runOnPlaybackApplicationThread { controller.pause() }

    override fun stop() = controller.runOnPlaybackApplicationThread { controller.stop() }

    override suspend fun currentTimeline(): PlaybackTimelineState =
        onPlaybackApplicationThread {
            controller.toStableTimeline().also(::publishTimeline)
        }

    override suspend fun seekRelative(
        direction: PlaybackSeekDirection,
        timeoutMillis: Long,
    ): StableSeekResult {
        val token = onPlaybackApplicationThread { controller.currentPlaybackSeekToken() }
            ?: return StableSeekResult.Rejected(StableSeekRejectReason.COMMAND_UNAVAILABLE)
        val result = try {
            controller.awaitPlaybackSeek(
                request = PlaybackSeekRequest.Relative(
                    token = token,
                    direction = direction.sign,
                ),
                timeoutMillis = timeoutMillis,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MediaControllerOperationException) {
            throw PlaybackSessionOperationException(error.failure.toStableFailure())
        } catch (_: Exception) {
            throw PlaybackSessionOperationException(PlaybackSessionOperationFailure.CommandFailed)
        }
        return when (result) {
            is PlaybackSeekResult.Accepted -> StableSeekResult.Accepted(
                targetMs = result.targetMs,
                direction = direction,
            )
            is PlaybackSeekResult.Rejected -> StableSeekResult.Rejected(result.reason.toStableReason())
            null -> StableSeekResult.Rejected(StableSeekRejectReason.CONTROLLER_REJECTED)
        }
    }

    override fun selectAudioTrack(key: TrackKey) {
        controller.runOnPlaybackApplicationThread {
            Media3TrackController.selectAudioTrack(controller, key.groupId, key.trackIndex)
        }
    }

    override fun selectSubtitleTrack(key: TrackKey) {
        controller.runOnPlaybackApplicationThread {
            Media3TrackController.selectTextTrack(controller, key.groupId, key.trackIndex)
        }
    }

    override fun disableSubtitles() {
        controller.runOnPlaybackApplicationThread {
            Media3TrackController.disableTextTracks(controller)
        }
    }

    override fun close() {
        controller.runOnPlaybackApplicationThread {
            if (!closed) {
                closed = true
                controller.removeListener(listener)
            }
        }
    }

    private fun publishTimeline(timeline: PlaybackTimelineState) {
        if (closed) return
        val current = mutableState.value
        mutableState.value = current.copy(
            capabilities = current.capabilities.copy(
                hasKnownDuration = timeline.hasKnownDuration,
                isLive = timeline.isLive,
            ),
            timeline = timeline,
        )
    }

    private fun publishSnapshot(player: Player) {
        if (closed) return
        val trackSelection = Media3TrackController.snapshot(player.trackSelectionParameters)
        val timeline = player.toStableTimeline()
        val phase = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackSessionPhase.BUFFERING
            Player.STATE_READY -> PlaybackSessionPhase.READY
            Player.STATE_ENDED -> PlaybackSessionPhase.ENDED
            else -> PlaybackSessionPhase.IDLE
        }
        mutableState.value = PlaybackSessionSnapshot(
            phase = phase,
            isPlaying = player.isPlaying && phase == PlaybackSessionPhase.READY,
            hasError = player.playerError != null,
            capabilities = derivePlayerCapabilities(
                availableCommands = player.availableCommands.toIntSet(),
                tracks = player.currentTracks,
                durationMs = player.duration,
                isLive = timeline.isLive,
                favoriteSupported = false,
            ),
            timeline = timeline,
            audioTracks = projector.audioTracks(
                tracks = player.currentTracks,
                selectedGroup = trackSelection.selectedAudioGroup,
                selectedIndices = trackSelection.selectedAudioIndices,
            ),
            subtitleTracks = projector.textTracks(
                tracks = player.currentTracks,
                selectedGroup = trackSelection.selectedTextGroup,
                selectedIndices = trackSelection.selectedTextIndices,
            ),
            subtitlesDisabled = trackSelection.textDisabled,
        )
    }

    private suspend fun <T> onPlaybackApplicationThread(block: () -> T): T {
        if (controller.applicationLooper.thread === Thread.currentThread()) return block()
        return suspendCancellableCoroutine { continuation ->
            val posted = Handler(controller.applicationLooper).post {
                if (!continuation.isActive) return@post
                try {
                    continuation.resume(block())
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
            if (!posted && continuation.isActive) {
                continuation.resumeWithException(
                    IllegalStateException("Playback application looper rejected command."),
                )
            }
        }
    }

    private companion object {
        fun emptySnapshot() = PlaybackSessionSnapshot(
            phase = PlaybackSessionPhase.IDLE,
            isPlaying = false,
            hasError = false,
            capabilities = app.muxtv.player.PlayerCapabilities(
                canSeek = false,
                canPause = false,
                canSetTrackSelection = false,
                hasAudioTracks = false,
                hasTextTracks = false,
                supportsFavorite = false,
                hasKnownDuration = false,
                isLive = false,
            ),
            timeline = PlaybackTimelineState(0L, null, isLive = false),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            subtitlesDisabled = false,
        )
    }
}

private fun Player.toStableTimeline(): PlaybackTimelineState = PlaybackTimelineState(
    positionMs = currentPosition.coerceAtLeast(0L),
    durationMs = duration.takeIf { it != C.TIME_UNSET && it > 0L },
    isLive = isCurrentMediaItemLive,
)

private fun MediaController.runOnPlaybackApplicationThread(block: () -> Unit) {
    if (applicationLooper.thread === Thread.currentThread()) {
        block()
    } else {
        check(Handler(applicationLooper).post(block)) {
            "Playback application looper rejected command."
        }
    }
}

private fun MediaControllerOperationFailure.toStableFailure(): PlaybackSessionOperationFailure = when (this) {
    MediaControllerOperationFailure.ConnectorClosed -> PlaybackSessionOperationFailure.ConnectorClosed
    MediaControllerOperationFailure.ConnectionTimedOut -> PlaybackSessionOperationFailure.ConnectionTimedOut
    MediaControllerOperationFailure.ConnectionCancelled -> PlaybackSessionOperationFailure.ConnectionCancelled
    MediaControllerOperationFailure.ConnectionFailed -> PlaybackSessionOperationFailure.ConnectionFailed
    MediaControllerOperationFailure.CommandTimedOut -> PlaybackSessionOperationFailure.CommandTimedOut
    MediaControllerOperationFailure.CommandCancelled -> PlaybackSessionOperationFailure.CommandCancelled
    MediaControllerOperationFailure.CommandFailed -> PlaybackSessionOperationFailure.CommandFailed
}

private fun PlaybackSeekRejectReason.toStableReason(): StableSeekRejectReason = when (this) {
    PlaybackSeekRejectReason.STALE_PLAYBACK -> StableSeekRejectReason.STALE_PLAYBACK
    PlaybackSeekRejectReason.COMMAND_UNAVAILABLE -> StableSeekRejectReason.COMMAND_UNAVAILABLE
    PlaybackSeekRejectReason.LIVE_CONTENT -> StableSeekRejectReason.LIVE_CONTENT
    PlaybackSeekRejectReason.UNKNOWN_DURATION -> StableSeekRejectReason.UNKNOWN_DURATION
    PlaybackSeekRejectReason.INVALID_POSITION -> StableSeekRejectReason.INVALID_POSITION
    PlaybackSeekRejectReason.INVALID_TARGET -> StableSeekRejectReason.INVALID_TARGET
    PlaybackSeekRejectReason.CONTROLLER_REJECTED -> StableSeekRejectReason.CONTROLLER_REJECTED
}

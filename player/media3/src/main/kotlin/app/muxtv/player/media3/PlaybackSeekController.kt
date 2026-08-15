package app.muxtv.player.media3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Kodi-like interaction state for remote seek input, Media3-native execution.
 *
 * Rapid D-pad input immediately updates the virtual target and restarts the coalesce window;
 * exactly one [onApplySeek] fires per burst. New playback generations discard pending/applying
 * state from the previous session: stale generations can never seek new media.
 *
 * The class is deliberately free of Media3 types: generation is an opaque identity supplied by
 * the host (channel id / external session id), and the actual `player.seekTo` is performed by
 * the host callback. All state mutations happen on the [scope] dispatcher.
 */
class PlaybackSeekController(
    private val scope: CoroutineScope,
    private val onApplySeek: (generation: Any, targetMs: Long) -> Unit,
    private val stepMillis: Long = PlaybackSeekPolicy.STEP_MILLIS,
    private val quietWindowMillis: Long = PlaybackSeekPolicy.QUIET_WINDOW_MILLIS,
    private val hudLingerMillis: Long = PlaybackSeekPolicy.HUD_LINGER_MILLIS,
) {
    private val mutableState = MutableStateFlow<SeekControllerState>(SeekControllerState.Idle)
    val state: StateFlow<SeekControllerState> = mutableState.asStateFlow()

    private var generation: Any? = null
    private var basePositionMs = UNSET_POSITION
    private var pendingTargetMs = UNSET_POSITION
    private var lastTargetMs = UNSET_POSITION
    private var lastDirection = DIRECTION_NONE
    private var applyJob: Job? = null
    private var lingerJob: Job? = null

    /**
     * Registers one remote seek request. Returns false when the input was rejected (unknown
     * duration/non-seekable), so hosts can avoid consuming the key event.
     */
    fun onDirectionRequested(
        generation: Any,
        direction: Int,
        currentPositionMs: Long,
        durationMs: Long?,
    ): Boolean {
        require(direction == DIRECTION_BACKWARD || direction == DIRECTION_FORWARD) {
            "direction must be -1 or +1"
        }
        if (durationMs == null || durationMs <= 0L || currentPositionMs < 0L) return false
        if (this.generation != generation) {
            beginBurst(generation, currentPositionMs)
        } else if (mutableState.value is SeekControllerState.Applying) {
            return false
        }
        if (pendingTargetMs == UNSET_POSITION) {
            basePositionMs = currentPositionMs
        }
        val start = if (pendingTargetMs != UNSET_POSITION) pendingTargetMs else basePositionMs
        val target = (start + direction * stepMillis).coerceIn(0L, durationMs)
        pendingTargetMs = target
        lastTargetMs = target
        lastDirection = directionOf(target - basePositionMs)
        mutableState.value = SeekControllerState.Pending(target, lastDirection)
        scheduleApply(generation)
        return true
    }

    /** Called by the host when Media3 reports the applied seek (position discontinuity). */
    fun onSeekConfirmed(generation: Any) {
        if (this.generation != generation) return
        if (mutableState.value !is SeekControllerState.Applying) return
        applyJob?.cancel()
        applyJob = null
        mutableState.value = SeekControllerState.Completed(lastTargetMs, lastDirection)
        scheduleIdle(hudLingerMillis)
    }

    /** Drops all pending/applying/linger state. Call on session replace or composition teardown. */
    fun reset() {
        applyJob?.cancel()
        applyJob = null
        lingerJob?.cancel()
        lingerJob = null
        generation = null
        basePositionMs = UNSET_POSITION
        pendingTargetMs = UNSET_POSITION
        lastTargetMs = UNSET_POSITION
        lastDirection = DIRECTION_NONE
        mutableState.value = SeekControllerState.Idle
    }

    private fun beginBurst(generation: Any, basePositionMs: Long) {
        applyJob?.cancel()
        applyJob = null
        this.generation = generation
        this.basePositionMs = basePositionMs
        this.pendingTargetMs = UNSET_POSITION
    }

    private fun scheduleApply(generation: Any) {
        applyJob?.cancel()
        lingerJob?.cancel()
        lingerJob = null
        applyJob = scope.launch {
            delay(quietWindowMillis)
            applyJob = null
            if (this@PlaybackSeekController.generation != generation) return@launch
            val target = pendingTargetMs
            if (target == UNSET_POSITION) return@launch
            pendingTargetMs = UNSET_POSITION
            mutableState.value = SeekControllerState.Applying(target, lastDirection)
            runCatching { onApplySeek(generation, target) }
            scheduleIdle(APPLY_TIMEOUT_MILLIS + hudLingerMillis)
        }
    }

    private fun scheduleIdle(delayMillis: Long) {
        lingerJob?.cancel()
        lingerJob = scope.launch {
            delay(delayMillis)
            lingerJob = null
            val current = mutableState.value
            if (current is SeekControllerState.Applying || current is SeekControllerState.Completed) {
                mutableState.value = SeekControllerState.Idle
            }
        }
    }

    private fun directionOf(delta: Long): Int = when {
        delta > 0L -> DIRECTION_FORWARD
        delta < 0L -> DIRECTION_BACKWARD
        else -> DIRECTION_NONE
    }

    companion object {
        const val DIRECTION_NONE = 0
        const val DIRECTION_BACKWARD = -1
        const val DIRECTION_FORWARD = 1
        const val APPLY_TIMEOUT_MILLIS = 2_000L
        private const val UNSET_POSITION = Long.MIN_VALUE
    }
}

/** Presentation projection of the coalesced seek for the HUD/timeline preview. */
sealed interface SeekControllerState {
    data object Idle : SeekControllerState

    data class Pending(
        val targetMs: Long,
        val direction: Int,
    ) : SeekControllerState

    data class Applying(
        val targetMs: Long,
        val direction: Int,
    ) : SeekControllerState

    data class Completed(
        val targetMs: Long,
        val direction: Int,
    ) : SeekControllerState
}

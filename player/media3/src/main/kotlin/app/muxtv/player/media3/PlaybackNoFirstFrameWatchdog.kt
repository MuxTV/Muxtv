package app.muxtv.player.media3

internal enum class PlaybackNoFirstFrameWatchdogVariant {
    A_CURRENT_GLOBAL_ONLY,
    B_READY_GATED_10S,
    C_READY_GATED_5S,
}

internal sealed interface PlaybackNoFirstFrameWatchdogAction {
    data object None : PlaybackNoFirstFrameWatchdogAction
    data class Arm(
        val token: PlaybackAttemptToken,
        val timeoutMillis: Long,
        val windowId: Long,
    ) : PlaybackNoFirstFrameWatchdogAction
    data object Disarm : PlaybackNoFirstFrameWatchdogAction
    data class Expired(
        val token: PlaybackAttemptToken,
    ) : PlaybackNoFirstFrameWatchdogAction
}

internal class PlaybackNoFirstFrameWatchdog(
    private val variant: PlaybackNoFirstFrameWatchdogVariant,
) {
    private var active: AttemptState? = null
    private var nextWindowId: Long = 0L

    fun activate(token: PlaybackAttemptToken): PlaybackNoFirstFrameWatchdogAction {
        val action = if (active?.armedWindowId != null) {
            PlaybackNoFirstFrameWatchdogAction.Disarm
        } else {
            PlaybackNoFirstFrameWatchdogAction.None
        }
        active = AttemptState(token = token)
        return action
    }

    fun onReadyChanged(
        token: PlaybackAttemptToken,
        ready: Boolean,
    ): PlaybackNoFirstFrameWatchdogAction = update(token) { it.ready = ready }

    fun onVideoExpectedChanged(
        token: PlaybackAttemptToken,
        expected: Boolean,
    ): PlaybackNoFirstFrameWatchdogAction = update(token) { it.videoExpected = expected }

    fun onSurfaceAvailabilityChanged(
        token: PlaybackAttemptToken,
        available: Boolean,
    ): PlaybackNoFirstFrameWatchdogAction = update(token) { it.surfaceAvailable = available }

    fun onRenderedFirstFrame(
        token: PlaybackAttemptToken,
    ): PlaybackNoFirstFrameWatchdogAction {
        val state = current(token) ?: return PlaybackNoFirstFrameWatchdogAction.None
        state.firstFrameReported = true
        return reconcile(state)
    }

    fun onTimerFired(
        token: PlaybackAttemptToken,
        windowId: Long,
    ): PlaybackNoFirstFrameWatchdogAction {
        val state = current(token) ?: return PlaybackNoFirstFrameWatchdogAction.None
        if (
            state.armedWindowId != windowId ||
            !state.isEligible() ||
            state.expired
        ) {
            return PlaybackNoFirstFrameWatchdogAction.None
        }
        state.armedWindowId = null
        state.expired = true
        return PlaybackNoFirstFrameWatchdogAction.Expired(token)
    }

    private fun update(
        token: PlaybackAttemptToken,
        mutation: (AttemptState) -> Unit,
    ): PlaybackNoFirstFrameWatchdogAction {
        val state = current(token) ?: return PlaybackNoFirstFrameWatchdogAction.None
        mutation(state)
        return reconcile(state)
    }

    private fun current(token: PlaybackAttemptToken): AttemptState? =
        active?.takeIf { it.token == token }

    private fun reconcile(state: AttemptState): PlaybackNoFirstFrameWatchdogAction {
        val timeoutMillis = variant.timeoutMillis()
        val eligible = timeoutMillis != null && state.isEligible()
        return when {
            eligible && state.armedWindowId == null -> {
                val windowId = allocateWindowId()
                state.armedWindowId = windowId
                PlaybackNoFirstFrameWatchdogAction.Arm(
                    token = state.token,
                    timeoutMillis = requireNotNull(timeoutMillis),
                    windowId = windowId,
                )
            }
            !eligible && state.armedWindowId != null -> {
                state.armedWindowId = null
                PlaybackNoFirstFrameWatchdogAction.Disarm
            }
            else -> PlaybackNoFirstFrameWatchdogAction.None
        }
    }

    private fun allocateWindowId(): Long {
        check(nextWindowId < Long.MAX_VALUE) { "C12 watchdog window id exhausted" }
        nextWindowId += 1L
        return nextWindowId
    }

    private fun AttemptState.isEligible(): Boolean =
        ready &&
            videoExpected &&
            surfaceAvailable &&
            !firstFrameReported &&
            !expired

    private data class AttemptState(
        val token: PlaybackAttemptToken,
        var ready: Boolean = false,
        var videoExpected: Boolean = false,
        var surfaceAvailable: Boolean = false,
        var firstFrameReported: Boolean = false,
        var armedWindowId: Long? = null,
        var expired: Boolean = false,
    )
}

private fun PlaybackNoFirstFrameWatchdogVariant.timeoutMillis(): Long? = when (this) {
    PlaybackNoFirstFrameWatchdogVariant.A_CURRENT_GLOBAL_ONLY -> null
    PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S -> 10_000L
    PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S -> 5_000L
}

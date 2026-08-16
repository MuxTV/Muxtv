package app.muxtv.feature.player

/**
 * Normalized playback commands produced by a platform TV-remote boundary.
 *
 * This type deliberately carries no Android [android.view.KeyEvent]. The Activity owns translation
 * from native input while the player surface keeps seek eligibility and policy ownership.
 */
enum class PlayerRemoteCommand {
    SEEK_BACKWARD,
    SEEK_FORWARD,
}

fun interface PlayerRemoteCommandHandler {
    fun onCommand(command: PlayerRemoteCommand): Boolean
}

/**
 * Bounded, non-content diagnostic snapshot for the native TV-input bridge.
 *
 * It intentionally records only registration/dispatch control-flow state. No media identity,
 * locator, title, channel, or user input payload is retained.
 */
data class PlayerRemoteInputDiagnostics(
    val attachGeneration: Long,
    val hasActiveHandler: Boolean,
    val dispatchCount: Long,
    val lastDispatchHadActiveHandler: Boolean?,
    val lastDispatchHandled: Boolean?,
)

/**
 * Single-consumer bridge between native TV input and the active playback surface.
 *
 * A newer registration replaces the previous one. Closing an older registration is identity-safe
 * and cannot detach the newer handler, which makes Compose disposal/recomposition deterministic.
 */
class PlayerRemoteInputHost {
    private var active: Registration? = null
    private var attachGeneration = 0L
    private var dispatchCount = 0L
    private var lastDispatchHadActiveHandler: Boolean? = null
    private var lastDispatchHandled: Boolean? = null

    fun attach(handler: PlayerRemoteCommandHandler): AutoCloseable {
        val registration = Registration(handler)
        attachGeneration += 1L
        active = registration
        return AutoCloseable {
            if (active === registration) {
                active = null
            }
        }
    }

    fun dispatch(command: PlayerRemoteCommand): Boolean {
        val registration = active
        val hadActiveHandler = registration != null
        val handled = registration?.handler?.onCommand(command) == true
        dispatchCount += 1L
        lastDispatchHadActiveHandler = hadActiveHandler
        lastDispatchHandled = handled
        return handled
    }

    fun diagnosticsSnapshot(): PlayerRemoteInputDiagnostics = PlayerRemoteInputDiagnostics(
        attachGeneration = attachGeneration,
        hasActiveHandler = active != null,
        dispatchCount = dispatchCount,
        lastDispatchHadActiveHandler = lastDispatchHadActiveHandler,
        lastDispatchHandled = lastDispatchHandled,
    )

    private class Registration(
        val handler: PlayerRemoteCommandHandler,
    )
}

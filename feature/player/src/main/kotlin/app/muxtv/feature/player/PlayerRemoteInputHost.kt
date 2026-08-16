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
 * Single-consumer bridge between native TV input and the active playback surface.
 *
 * A newer registration replaces the previous one. Closing an older registration is identity-safe
 * and cannot detach the newer handler, which makes Compose disposal/recomposition deterministic.
 */
class PlayerRemoteInputHost {
    private var active: Registration? = null

    fun attach(handler: PlayerRemoteCommandHandler): AutoCloseable {
        val registration = Registration(handler)
        active = registration
        return AutoCloseable {
            if (active === registration) {
                active = null
            }
        }
    }

    fun dispatch(command: PlayerRemoteCommand): Boolean =
        active?.handler?.onCommand(command) == true

    private class Registration(
        val handler: PlayerRemoteCommandHandler,
    )
}

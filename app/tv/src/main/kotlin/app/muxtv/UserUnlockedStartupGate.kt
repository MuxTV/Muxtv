package app.muxtv

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class UserUnlockedStartupGate(
    private val isUserUnlocked: () -> Boolean,
    private val registerUnlockListener: ((() -> Unit) -> UserUnlockRegistration),
    private val onUnlocked: () -> Unit,
) {
    private val startupStarted = AtomicBoolean(false)
    private val registrationStarted = AtomicBoolean(false)
    private val unlockRegistration = AtomicReference<UserUnlockRegistration?>(null)

    fun start() {
        if (startupStarted.get()) return
        if (isUserUnlocked()) {
            publishUnlocked()
            return
        }

        registerForUnlockIfNeeded()
        // Close the check -> receiver-registration race: the unlock broadcast may have
        // happened between the first state read and the receiver becoming active.
        if (isUserUnlocked()) {
            publishUnlocked()
        }
    }

    private fun registerForUnlockIfNeeded() {
        if (!registrationStarted.compareAndSet(false, true)) return

        val registration = try {
            registerUnlockListener(::handleUnlockSignal)
        } catch (throwable: Throwable) {
            registrationStarted.set(false)
            throw throwable
        }

        unlockRegistration.set(registration)
        // A listener implementation is allowed to call back synchronously while it
        // is being registered. If startup already won that race, release the handle
        // that became available only after the callback returned.
        if (startupStarted.get()) {
            unregisterUnlockListener()
        }
    }

    private fun handleUnlockSignal() {
        // ACTION_USER_UNLOCKED is only a wake-up signal. The current user's state is
        // the authority, so a spurious/foreign signal cannot release CE-backed work.
        if (isUserUnlocked()) {
            publishUnlocked()
        }
    }

    private fun publishUnlocked() {
        if (!startupStarted.compareAndSet(false, true)) return
        unregisterUnlockListener()
        onUnlocked()
    }

    private fun unregisterUnlockListener() {
        unlockRegistration.getAndSet(null)?.release()
    }
}

internal fun interface UserUnlockRegistration {
    fun release()
}

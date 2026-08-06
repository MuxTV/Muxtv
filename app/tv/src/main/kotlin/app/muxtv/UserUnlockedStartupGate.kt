package app.muxtv

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun interface UserUnlockRegistration {
    fun unregister()
}

/**
 * Process-local gate for work that depends on credential-encrypted storage.
 *
 * The state is intentionally independent from Android classes so the race between checking the
 * current user state and registering for ACTION_USER_UNLOCKED can be verified with JVM tests.
 */
internal class UserUnlockedStartupGate(
    private val isUserUnlocked: () -> Boolean,
    private val registerUnlockListener: (onUnlocked: () -> Unit) -> UserUnlockRegistration,
    private val onUnlocked: () -> Unit,
) {
    private val startupStarted = AtomicBoolean(false)
    private val registrationStarted = AtomicBoolean(false)
    private val registration = AtomicReference<UserUnlockRegistration?>(null)

    fun start() {
        if (startupStarted.get()) return

        if (isUserUnlocked()) {
            publishUnlocked()
            return
        }

        if (registrationStarted.compareAndSet(false, true)) {
            val newRegistration = try {
                registerUnlockListener(::handleUnlockSignal)
            } catch (error: Throwable) {
                registrationStarted.set(false)
                throw error
            }
            registration.set(newRegistration)

            // A listener can fire before registerUnlockListener returns. In that race the startup
            // flag is already set while the registration handle is not yet visible to the callback.
            if (startupStarted.get()) {
                unregisterListener()
                return
            }
        }

        // ACTION_USER_UNLOCKED is not a state store. Re-read the authoritative state after
        // registration so an unlock between the first check and receiver registration is not lost.
        if (isUserUnlocked()) {
            publishUnlocked()
        }
    }

    private fun handleUnlockSignal() {
        // Android documents that user state may have changed by broadcast delivery time.
        if (!isUserUnlocked()) return
        publishUnlocked()
    }

    private fun publishUnlocked() {
        val shouldStart = startupStarted.compareAndSet(false, true)
        unregisterListener()
        if (shouldStart) {
            onUnlocked()
        }
    }

    private fun unregisterListener() {
        registration.getAndSet(null)?.unregister()
    }
}

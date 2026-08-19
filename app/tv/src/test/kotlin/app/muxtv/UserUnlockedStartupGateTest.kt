package app.muxtv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserUnlockedStartupGateTest {
    @Test
    fun `already unlocked starts once without registering listener`() {
        var starts = 0
        var registrations = 0
        val gate = UserUnlockedStartupGate(
            isUserUnlocked = { true },
            registerUnlockListener = {
                registrations += 1
                UserUnlockRegistration {}
            },
            onUnlocked = { starts += 1 },
        )

        gate.start()
        gate.start()

        assertThat(starts).isEqualTo(1)
        assertThat(registrations).isEqualTo(0)
    }

    @Test
    fun `locked startup defers until current user is unlocked`() {
        var unlocked = false
        var starts = 0
        var registrations = 0
        var unregisters = 0
        var listener: (() -> Unit)? = null
        val gate = UserUnlockedStartupGate(
            isUserUnlocked = { unlocked },
            registerUnlockListener = { callback ->
                registrations += 1
                listener = callback
                UserUnlockRegistration { unregisters += 1 }
            },
            onUnlocked = { starts += 1 },
        )

        gate.start()
        listener?.invoke()

        assertThat(starts).isEqualTo(0)
        assertThat(registrations).isEqualTo(1)
        assertThat(unregisters).isEqualTo(0)

        unlocked = true
        listener?.invoke()

        assertThat(starts).isEqualTo(1)
        assertThat(unregisters).isEqualTo(1)
    }

    @Test
    fun `duplicate starts and unlock signals remain exactly once`() {
        var unlocked = false
        var starts = 0
        var registrations = 0
        var unregisters = 0
        var listener: (() -> Unit)? = null
        val gate = UserUnlockedStartupGate(
            isUserUnlocked = { unlocked },
            registerUnlockListener = { callback ->
                registrations += 1
                listener = callback
                UserUnlockRegistration { unregisters += 1 }
            },
            onUnlocked = { starts += 1 },
        )

        gate.start()
        gate.start()
        gate.start()

        assertThat(registrations).isEqualTo(1)
        assertThat(starts).isEqualTo(0)

        unlocked = true
        listener?.invoke()
        listener?.invoke()
        gate.start()

        assertThat(starts).isEqualTo(1)
        assertThat(unregisters).isEqualTo(1)
        assertThat(registrations).isEqualTo(1)
    }

    @Test
    fun `post registration recheck closes unlock race`() {
        var stateReads = 0
        var starts = 0
        var registrations = 0
        var unregisters = 0
        val gate = UserUnlockedStartupGate(
            isUserUnlocked = {
                stateReads += 1
                stateReads >= 2
            },
            registerUnlockListener = {
                registrations += 1
                UserUnlockRegistration { unregisters += 1 }
            },
            onUnlocked = { starts += 1 },
        )

        gate.start()

        assertThat(stateReads).isAtLeast(2)
        assertThat(registrations).isEqualTo(1)
        assertThat(starts).isEqualTo(1)
        assertThat(unregisters).isEqualTo(1)
    }

    @Test
    fun `synchronous unlock signal during registration releases eventual registration`() {
        var unlocked = false
        var starts = 0
        var unregisters = 0
        val gate = UserUnlockedStartupGate(
            isUserUnlocked = { unlocked },
            registerUnlockListener = { callback ->
                unlocked = true
                callback()
                UserUnlockRegistration { unregisters += 1 }
            },
            onUnlocked = { starts += 1 },
        )

        gate.start()

        assertThat(starts).isEqualTo(1)
        assertThat(unregisters).isEqualTo(1)
    }
}

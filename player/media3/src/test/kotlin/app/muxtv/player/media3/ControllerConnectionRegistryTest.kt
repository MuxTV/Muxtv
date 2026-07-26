package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertThrows
import org.junit.Test

class ControllerConnectionRegistryTest {
    @Test
    fun `concurrent acquire shares pending future and caches success`() {
        val pending = SettableFuture.create<ControllerRef>()
        val starts = AtomicInteger()
        val registry = registry()

        val first = registry.acquire {
            starts.incrementAndGet()
            pending
        }
        val second = registry.acquire {
            starts.incrementAndGet()
            SettableFuture.create()
        }

        assertThat(second).isSameInstanceAs(first)
        assertThat(starts.get()).isEqualTo(1)

        val connected = ControllerRef("connected")
        pending.set(connected)
        registry.complete(pending, Result.success(connected))

        val cached = registry.acquire {
            starts.incrementAndGet()
            SettableFuture.create()
        }
        assertThat(cached.get()).isSameInstanceAs(connected)
        assertThat(starts.get()).isEqualTo(1)
    }

    @Test
    fun `failed future returns to idle and next acquire starts again`() {
        val first = SettableFuture.create<ControllerRef>()
        val second = SettableFuture.create<ControllerRef>()
        val starts = AtomicInteger()
        val registry = registry()

        val acquiredFirst = registry.acquire {
            starts.incrementAndGet()
            first
        }
        val failure = IllegalStateException("synthetic connection failure")
        first.setException(failure)
        registry.complete(acquiredFirst, Result.failure(failure))

        val acquiredSecond = registry.acquire {
            starts.incrementAndGet()
            second
        }

        assertThat(acquiredSecond).isSameInstanceAs(second)
        assertThat(starts.get()).isEqualTo(2)
    }

    @Test
    fun `cancelled future returns to idle and next acquire starts again`() {
        val first = SettableFuture.create<ControllerRef>()
        val second = SettableFuture.create<ControllerRef>()
        val starts = AtomicInteger()
        val registry = registry()

        val acquiredFirst = registry.acquire {
            starts.incrementAndGet()
            first
        }
        first.cancel(true)
        registry.complete(acquiredFirst, Result.failure(CancellationException("synthetic cancel")))

        val acquiredSecond = registry.acquire {
            starts.incrementAndGet()
            second
        }

        assertThat(acquiredSecond).isSameInstanceAs(second)
        assertThat(starts.get()).isEqualTo(2)
    }

    @Test
    fun `disconnect invalidates only the matching connected instance`() {
        val firstFuture = SettableFuture.create<ControllerRef>()
        val secondFuture = SettableFuture.create<ControllerRef>()
        val starts = AtomicInteger()
        val registry = registry()
        val connected = ControllerRef("connected")

        registry.acquire {
            starts.incrementAndGet()
            firstFuture
        }
        firstFuture.set(connected)
        registry.complete(firstFuture, Result.success(connected))

        registry.disconnected(ControllerRef("other"))
        assertThat(registry.acquire { error("matching controller must remain cached") }.get())
            .isSameInstanceAs(connected)

        registry.disconnected(connected)
        val afterDisconnect = registry.acquire {
            starts.incrementAndGet()
            secondFuture
        }

        assertThat(afterDisconnect).isSameInstanceAs(secondFuture)
        assertThat(starts.get()).isEqualTo(2)
    }

    @Test
    fun `close releases connected instance exactly once`() {
        val connectedReleases = AtomicInteger()
        val future = SettableFuture.create<ControllerRef>()
        val registry = registry(releaseConnected = { connectedReleases.incrementAndGet() })
        val connected = ControllerRef("connected")

        registry.acquire { future }
        future.set(connected)
        registry.complete(future, Result.success(connected))

        registry.close()
        registry.close()

        assertThat(connectedReleases.get()).isEqualTo(1)
    }

    @Test
    fun `close releases pending future exactly once`() {
        val pendingReleases = AtomicInteger()
        val future = SettableFuture.create<ControllerRef>()
        val registry = registry(releasePending = { pendingReleases.incrementAndGet() })

        registry.acquire { future }
        registry.close()
        registry.close()

        assertThat(pendingReleases.get()).isEqualTo(1)
    }

    @Test
    fun `late success after close is released instead of cached`() {
        val pendingReleases = AtomicInteger()
        val connectedReleases = AtomicInteger()
        val future = SettableFuture.create<ControllerRef>()
        val registry = registry(
            releasePending = { pendingReleases.incrementAndGet() },
            releaseConnected = { connectedReleases.incrementAndGet() },
        )
        val late = ControllerRef("late")

        registry.acquire { future }
        registry.close()
        future.set(late)
        registry.complete(future, Result.success(late))

        assertThat(pendingReleases.get()).isEqualTo(1)
        assertThat(connectedReleases.get()).isEqualTo(1)
    }

    @Test
    fun `acquire after close returns a failed future`() {
        val registry = registry()
        registry.close()

        val future = registry.acquire { error("closed registry must not start a connection") }
        val error = assertThrows(ExecutionException::class.java) { future.get() }

        assertThat(error.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(error.cause!!.message).isEqualTo("Controller connection registry is closed.")
    }

    private fun registry(
        releasePending: (SettableFuture<ControllerRef>) -> Unit = {},
        releaseConnected: (ControllerRef) -> Unit = {},
    ): ControllerConnectionRegistry<ControllerRef> = ControllerConnectionRegistry(
        releasePending = { future -> releasePending(future as SettableFuture<ControllerRef>) },
        releaseConnected = releaseConnected,
    )

    private class ControllerRef(
        val label: String,
    )
}

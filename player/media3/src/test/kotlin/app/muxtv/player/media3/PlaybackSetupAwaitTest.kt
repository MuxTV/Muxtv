package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSetupAwaitTest {
    @Test
    fun `completed setup returns value without cancellation callback`() = runTest {
        val future = SettableFuture.create<String>()
        val cancellations = AtomicInteger()
        future.set("ready")

        val result = awaitPlaybackSetup(
            future = future,
            timeoutMillis = 1_000,
            cancelSetup = { cancellations.incrementAndGet() },
        )

        assertThat(result).isEqualTo("ready")
        assertThat(cancellations.get()).isEqualTo(0)
    }

    @Test
    fun `timeout cancels future and posts setup cancellation exactly once`() = runTest {
        val future = SettableFuture.create<String>()
        val cancellations = AtomicInteger()
        var failure: Throwable? = null

        val job = launch {
            failure = runCatching {
                awaitPlaybackSetup(
                    future = future,
                    timeoutMillis = 1_000,
                    cancelSetup = { cancellations.incrementAndGet() },
                )
            }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(1_001)
        job.join()

        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(future.isCancelled).isTrue()
        assertThat(cancellations.get()).isEqualTo(1)
    }

    @Test
    fun `parent cancellation cancels future and posts setup cancellation exactly once`() = runTest {
        val future = SettableFuture.create<String>()
        val cancellations = AtomicInteger()
        val job = launch {
            awaitPlaybackSetup(
                future = future,
                timeoutMillis = 10_000,
                cancelSetup = { cancellations.incrementAndGet() },
            )
        }
        runCurrent()

        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(future.isCancelled).isTrue()
        assertThat(cancellations.get()).isEqualTo(1)
    }

    @Test
    fun `cancellation callback failure never replaces the authoritative timeout`() = runTest {
        val future = SettableFuture.create<String>()
        var failure: Throwable? = null

        val job = launch {
            failure = runCatching {
                awaitPlaybackSetup(
                    future = future,
                    timeoutMillis = 1_000,
                    cancelSetup = { error("synthetic cancel callback failure") },
                )
            }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(1_001)
        job.join()

        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(failure!!.message).doesNotContain("synthetic")
    }
}

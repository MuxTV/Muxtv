package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ListenableFutureAwaitTest {
    @Test
    fun `completed future resumes with value`() = runTest {
        val future = SettableFuture.create<String>()
        future.set("ready")

        val result = future.awaitCancellable(
            timeoutMillis = 1_000,
            cancelFutureOnCancellation = false,
        )

        assertThat(result).isEqualTo("ready")
    }

    @Test
    fun `failed future resumes with original cause`() = runTest {
        val future = SettableFuture.create<String>()
        val expected = IllegalStateException("synthetic failure")
        future.setException(expected)

        val error = runCatching {
            future.awaitCancellable(
                timeoutMillis = 1_000,
                cancelFutureOnCancellation = false,
            )
        }.exceptionOrNull()

        assertThat(error).isSameInstanceAs(expected)
    }

    @Test
    fun `cancelled future reports future cancellation without cancelling parent`() = runTest {
        val future = SettableFuture.create<String>()
        future.cancel(true)

        val error = runCatching {
            future.awaitCancellable(
                timeoutMillis = 1_000,
                cancelFutureOnCancellation = false,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(ListenableFutureCancelledException::class.java)
        assertThat(coroutineContext[kotlinx.coroutines.Job]!!.isActive).isTrue()
    }

    @Test
    fun `timeout cancels command future when requested`() = runTest {
        val future = SettableFuture.create<String>()
        var error: Throwable? = null

        val job = launch {
            error = runCatching {
                future.awaitCancellable(
                    timeoutMillis = 1_000,
                    cancelFutureOnCancellation = true,
                )
            }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(1_001)
        job.join()

        assertThat(error).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(future.isCancelled).isTrue()
    }

    @Test
    fun `timeout does not cancel shared connection future`() = runTest {
        val future = SettableFuture.create<String>()
        var error: Throwable? = null

        val job = launch {
            error = runCatching {
                future.awaitCancellable(
                    timeoutMillis = 1_000,
                    cancelFutureOnCancellation = false,
                )
            }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(1_001)
        job.join()

        assertThat(error).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(future.isCancelled).isFalse()
        assertThat(future.isDone).isFalse()
    }

    @Test
    fun `parent cancellation ignores late success`() = runTest {
        val future = SettableFuture.create<String>()
        val resumed = AtomicBoolean(false)
        val job = launch {
            future.awaitCancellable(
                timeoutMillis = 10_000,
                cancelFutureOnCancellation = false,
            )
            resumed.set(true)
        }
        runCurrent()

        job.cancelAndJoin()
        future.set("late")
        runCurrent()

        assertThat(job.isCancelled).isTrue()
        assertThat(future.isCancelled).isFalse()
        assertThat(resumed.get()).isFalse()
    }
}

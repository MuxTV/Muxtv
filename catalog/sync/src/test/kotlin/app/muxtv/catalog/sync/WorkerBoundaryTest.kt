package app.muxtv.catalog.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class WorkerBoundaryTest {
    @Test
    fun returnsSuccessfulResult() = runBlocking {
        val result = runWorkerBoundary { 42 }

        assertThat(result.getOrThrow()).isEqualTo(42)
    }

    @Test
    fun wrapsRegularException() = runBlocking {
        val failure = IOException("network unavailable")

        val result = runWorkerBoundary<Int> { throw failure }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
    }

    @Test
    fun rethrowsCancellationException() = runBlocking {
        val cancellation = CancellationException("worker cancelled")

        try {
            runWorkerBoundary<Int> { throw cancellation }
            fail("CancellationException must be rethrown")
        } catch (actual: CancellationException) {
            assertThat(actual).isSameInstanceAs(cancellation)
        }
    }
}

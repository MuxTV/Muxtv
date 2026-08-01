package app.muxtv.catalog.sync

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class EpgRefreshCancellationContractTest {
    @Test
    fun `persistence failure cannot mask original coroutine cancellation`() {
        val expected = CancellationException("expected-cancellation")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                finalizeCancellationAndRethrow(expected) {
                    error("simulated persistence failure")
                }
            }
        }

        assertThat(thrown).isSameInstanceAs(expected)
    }
}

package app.muxtv.common.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundWorkFailureTest {
    @Test
    fun `failure kinds match stable WorkManager callbacks`() {
        assertThat(BackgroundWorkFailureKind.entries)
            .containsExactly(
                BackgroundWorkFailureKind.INITIALIZATION,
                BackgroundWorkFailureKind.SCHEDULING,
                BackgroundWorkFailureKind.WORKER_INITIALIZATION,
                BackgroundWorkFailureKind.WORKER_EXECUTION,
            )
            .inOrder()
    }

    @Test
    fun `worker category is a closed secret-safe vocabulary`() {
        assertThat(BackgroundWorkerCategory.entries)
            .containsExactly(
                BackgroundWorkerCategory.SOURCE_REFRESH,
                BackgroundWorkerCategory.EPG_REFRESH,
                BackgroundWorkerCategory.UNKNOWN,
            )
            .inOrder()
    }

    @Test
    fun `observation exposes no arbitrary string or throwable payload`() {
        val fieldTypes = BackgroundWorkFailureObservation::class.java.declaredFields.map { it.type }

        assertThat(fieldTypes).doesNotContain(String::class.java)
        assertThat(fieldTypes).doesNotContain(Throwable::class.java)
    }

    @Test
    fun `observation equality is deterministic`() {
        val first =
            BackgroundWorkFailureObservation(
                kind = BackgroundWorkFailureKind.WORKER_EXECUTION,
                timestampEpochMillis = 1234L,
                workerCategory = BackgroundWorkerCategory.SOURCE_REFRESH,
            )
        val second = first.copy()

        assertThat(second).isEqualTo(first)
        assertThat(second.hashCode()).isEqualTo(first.hashCode())
    }

    @Test
    fun `observation rejects negative wall clock timestamp`() {
        var thrown: Throwable? = null

        try {
            BackgroundWorkFailureObservation(
                kind = BackgroundWorkFailureKind.INITIALIZATION,
                timestampEpochMillis = -1L,
                workerCategory = BackgroundWorkerCategory.UNKNOWN,
            )
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }
}

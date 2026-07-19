package app.muxtv.common.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundedEventBufferTest {
    @Test
    fun `evicts oldest entries and preserves correlation IDs`() {
        val buffer = BoundedEventBuffer(capacity = 2)
        buffer.add(DiagnosticEvent("one", "source", "first"))
        buffer.add(DiagnosticEvent("two", "player", "second"))
        buffer.add(DiagnosticEvent("three", "player", "third"))

        assertThat(buffer.snapshot().map { it.correlationId }).containsExactly("two", "three").inOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capacity must be positive`() {
        BoundedEventBuffer(capacity = 0)
    }
}

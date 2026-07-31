package app.muxtv.catalog.refresh

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgPayloadDecoderResourceTest {
    @Test
    fun `transport failure during magic sniff closes input and propagates unchanged`() = runTest {
        val expected = IOException("private transport failure")
        val input = SniffFailureInputStream(expected)

        val actual = captureDecoderFailure {
            EpgPayloadDecoder().decode(input) { it.readBytes() }
        }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(input.closed).isTrue()
    }
}

private class SniffFailureInputStream(
    private val failure: IOException,
) : InputStream() {
    private var emitted = false

    var closed: Boolean = false
        private set

    override fun read(): Int {
        if (!emitted) {
            emitted = true
            return '<'.code
        }
        throw failure
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!emitted && length > 0) {
            emitted = true
            buffer[offset] = '<'.code.toByte()
            return 1
        }
        throw failure
    }

    override fun close() {
        closed = true
    }
}

private suspend fun captureDecoderFailure(block: suspend () -> Unit): Throwable =
    try {
        block()
        throw AssertionError("Expected block to fail.")
    } catch (error: Throwable) {
        error
    }
package app.muxtv.catalog.refresh

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgPayloadDecoderResourceTest {
    @Test
    fun `unsupported content encoding is rejected before payload read and closes input`() = runTest {
        val input = NoReadAllowedInputStream()

        val result = EpgPayloadDecoder().decode(
            input = input,
            hints = EpgPayloadHints(contentEncoding = "br"),
        ) { it.readBytes() }

        assertThat(result).isEqualTo(
            EpgPayloadDecodeResult.Rejected(
                EpgPayloadRejectionReason.UnsupportedContentEncoding,
            ),
        )
        assertThat(input.readAttempted).isFalse()
        assertThat(input.closed).isTrue()
    }

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

    @Test
    fun `gzip consumer cancellation closes compressed input and propagates unchanged`() = runTest {
        val expected = CancellationException("expected cancellation")
        val input = CloseTrackingInputStream(gzipResource("<tv/>".toByteArray()))

        val actual = captureDecoderFailure {
            EpgPayloadDecoder().decode(input) { decoded ->
                assertThat(decoded.read()).isNotEqualTo(-1)
                throw expected
            }
        }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(input.closed).isTrue()
    }
}

private class NoReadAllowedInputStream : InputStream() {
    var readAttempted: Boolean = false
        private set
    var closed: Boolean = false
        private set

    override fun read(): Int {
        readAttempted = true
        throw AssertionError("Unsupported content encoding must be rejected before reading payload bytes.")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        readAttempted = true
        throw AssertionError("Unsupported content encoding must be rejected before reading payload bytes.")
    }

    override fun close() {
        closed = true
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

private class CloseTrackingInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)

    var closed: Boolean = false
        private set

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun close() {
        closed = true
        delegate.close()
    }
}

private suspend fun captureDecoderFailure(block: suspend () -> Unit): Throwable =
    try {
        block()
        throw AssertionError("Expected block to fail.")
    } catch (error: Throwable) {
        error
    }

private fun gzipResource(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(bytes) }
    output.toByteArray()
}

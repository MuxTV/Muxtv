package app.muxtv.catalog.refresh

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgPayloadDecoderTest {
    private val decoder = EpgPayloadDecoder()

    @Test
    fun `plain XML is streamed through the consumer`() = runTest {
        val xml = "<tv><channel id=\"one\"/></tv>"
        val guarded = MaximumReadRequestInputStream(xml.toByteArray(), maxRequestedBytes = 8 * 1024)

        val result = decoder.decode(guarded) { input -> input.readBytes().decodeToString() }

        assertThat(result).isEqualTo(
            EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Plain, xml),
        )
    }

    @Test
    fun `gzip magic overrides HTTP hints`() = runTest {
        val xml = "<tv><programme channel=\"one\"/></tv>"

        val result = decoder.decode(
            input = ByteArrayInputStream(gzip(xml.toByteArray())),
            hints = EpgPayloadHints(
                contentEncoding = "br",
                contentType = "application/xml",
            ),
        ) { it.readBytes().decodeToString() }

        assertThat(result).isEqualTo(
            EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Gzip, xml),
        )
    }

    @Test
    fun `gzip hint is used when magic is inconclusive`() = runTest {
        val result = decoder.decode(
            input = ByteArrayInputStream("<tv/>".toByteArray()),
            hints = EpgPayloadHints(contentEncoding = "gzip"),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.MalformedGzip)
    }

    @Test
    fun `unsupported content encoding is rejected without echoing its value`() = runTest {
        val secretEncoding = "private-compression-token"

        val result = decoder.decode(
            input = ByteArrayInputStream("<tv/>".toByteArray()),
            hints = EpgPayloadHints(contentEncoding = secretEncoding),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.UnsupportedContentEncoding)
        assertThat(result.toString()).doesNotContain(secretEncoding)
    }

    @Test
    fun `empty payload is rejected`() = runTest {
        val result = decoder.decode(ByteArrayInputStream(byteArrayOf())) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.EmptyPayload)
    }

    @Test
    fun `plain decoded byte limit is enforced`() = runTest {
        val result = decoder.decode(
            input = ByteArrayInputStream("123456789".toByteArray()),
            limits = EpgPayloadDecodeLimits(maxDecodedBytes = 8),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.DecodedSizeExceeded)
    }

    @Test
    fun `gzip decoded byte limit is enforced after decompression`() = runTest {
        val result = decoder.decode(
            input = ByteArrayInputStream(gzip(ByteArray(32) { 7 })),
            limits = EpgPayloadDecodeLimits(maxDecodedBytes = 31),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.DecodedSizeExceeded)
    }

    @Test
    fun `ZIP skips bounded directories and streams first regular entry`() = runTest {
        val xml = "<tv><channel id=\"zip\"/></tv>"
        val archive = zip(
            ZipFixture("guide/", byteArrayOf(), directory = true),
            ZipFixture("guide/epg.xml", xml.toByteArray()),
            ZipFixture("ignored.xml", "<tv><channel id=\"ignored\"/></tv>".toByteArray()),
        )

        val result = decoder.decode(ByteArrayInputStream(archive)) {
            it.readBytes().decodeToString()
        }

        assertThat(result).isEqualTo(
            EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Zip, xml),
        )
    }

    @Test
    fun `empty ZIP is rejected as missing payload entry`() = runTest {
        val result = decoder.decode(ByteArrayInputStream(zip())) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.ZipPayloadEntryMissing)
    }

    @Test
    fun `ZIP without regular entry is rejected`() = runTest {
        val result = decoder.decode(
            ByteArrayInputStream(zip(ZipFixture("guide/", byteArrayOf(), directory = true))),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.ZipPayloadEntryMissing)
    }

    @Test
    fun `ZIP leading entry count is bounded`() = runTest {
        val archive = zip(
            ZipFixture("one/", byteArrayOf(), directory = true),
            ZipFixture("two/", byteArrayOf(), directory = true),
            ZipFixture("guide.xml", "<tv/>".toByteArray()),
        )

        val result = decoder.decode(
            input = ByteArrayInputStream(archive),
            limits = EpgPayloadDecodeLimits(maxLeadingZipEntries = 1),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.ZipLeadingEntryLimitExceeded)
    }

    @Test
    fun `ZIP entry name length is bounded without exposing name`() = runTest {
        val privateName = "private-provider-${"x".repeat(40)}.xml"
        val result = decoder.decode(
            input = ByteArrayInputStream(zip(ZipFixture(privateName, "<tv/>".toByteArray()))),
            limits = EpgPayloadDecodeLimits(maxZipEntryNameChars = 16),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.ZipEntryNameTooLong)
        assertThat(result.toString()).doesNotContain(privateName)
    }

    @Test
    fun `malformed gzip is a typed decoder failure`() = runTest {
        val result = decoder.decode(
            ByteArrayInputStream(byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x01)),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.MalformedGzip)
    }

    @Test
    fun `malformed ZIP is a typed decoder failure`() = runTest {
        val result = decoder.decode(
            ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x01)),
        ) { it.readBytes() }

        assertRejected(result, EpgPayloadRejectionReason.MalformedZip)
    }

    @Test
    fun `transport IOException propagates unchanged`() = runTest {
        val expected = IOException("private transport failure")
        val input = PrefixThenFailureInputStream("<tv>".toByteArray(), expected)

        val actual = captureFailure { decoder.decode(input) { it.readBytes() } }

        assertThat(actual).isSameInstanceAs(expected)
    }

    @Test
    fun `consumer exception propagates unchanged`() = runTest {
        val expected = ConsumerFailure()

        val actual = captureFailure {
            decoder.decode(ByteArrayInputStream("<tv/>".toByteArray())) { throw expected }
        }

        assertThat(actual).isSameInstanceAs(expected)
    }

    @Test
    fun `consumer cancellation propagates unchanged`() = runTest {
        val expected = CancellationException("expected cancellation")

        val actual = captureFailure {
            decoder.decode(ByteArrayInputStream("<tv/>".toByteArray())) { throw expected }
        }

        assertThat(actual).isSameInstanceAs(expected)
    }

    @Test
    fun `public diagnostics contain no hint or decoded values`() {
        val hints = EpgPayloadHints(
            contentEncoding = "private-encoding",
            contentType = "private-content-type",
        )
        val decoded = EpgPayloadDecodeResult.Decoded(
            EpgPayloadFormat.Plain,
            "private-programme-content",
        )

        assertThat(hints.toString()).doesNotContain("private-encoding")
        assertThat(hints.toString()).doesNotContain("private-content-type")
        assertThat(decoded.toString()).doesNotContain("private-programme-content")
    }

    private fun assertRejected(
        result: EpgPayloadDecodeResult<*>,
        reason: EpgPayloadRejectionReason,
    ) {
        assertThat(result).isEqualTo(EpgPayloadDecodeResult.Rejected(reason))
    }
}

private class ConsumerFailure : RuntimeException()

private suspend fun captureFailure(block: suspend () -> Unit): Throwable =
    try {
        block()
        throw AssertionError("Expected block to fail.")
    } catch (error: Throwable) {
        error
    }

private class MaximumReadRequestInputStream(
    bytes: ByteArray,
    private val maxRequestedBytes: Int,
) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(length <= maxRequestedBytes) { "Decoder requested an unexpectedly large read." }
        return delegate.read(buffer, offset, length)
    }

    override fun close() = delegate.close()
}

private class PrefixThenFailureInputStream(
    prefix: ByteArray,
    private val failure: IOException,
) : InputStream() {
    private val delegate = ByteArrayInputStream(prefix)

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) return value
        throw failure
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = delegate.read(buffer, offset, length)
        if (count >= 0) return count
        throw failure
    }
}

private data class ZipFixture(
    val name: String,
    val bytes: ByteArray,
    val directory: Boolean = false,
)

private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(bytes) }
    output.toByteArray()
}

private fun zip(vararg fixtures: ZipFixture): ByteArray = ByteArrayOutputStream().use { output ->
    ZipOutputStream(output).use { archive ->
        fixtures.forEach { fixture ->
            val entry = ZipEntry(fixture.name).apply {
                if (fixture.directory) size = 0
            }
            archive.putNextEntry(entry)
            if (!fixture.directory) archive.write(fixture.bytes)
            archive.closeEntry()
        }
    }
    output.toByteArray()
}
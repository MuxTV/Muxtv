package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingXmltvParserTest {
    @Test
    fun `streams bounded channels and programmes with redacted diagnostics`() = runTest {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="Synthetic Provider">
              <channel id="news.world">
                <display-name lang="en">News World</display-name>
                <display-name lang="ru">Новости мира</display-name>
                <icon src="https://images.example/news.png?token=secret" width="320" height="180"/>
                <url>https://provider.example/channel/news.world</url>
              </channel>
              <programme channel="news.world" start="20261025013000 +0200" stop="20261025023000 +0200">
                <title lang="en">Private programme title</title>
                <sub-title lang="en">Private episode title</sub-title>
                <desc lang="en">Private long description</desc>
                <category lang="en">News</category>
                <category lang="ru">Новости</category>
                <keyword>World</keyword>
                <country>GB</country>
                <episode-num system="xmltv_ns">0.0.</episode-num>
                <credits><director>Private Director</director></credits>
                <icon src="https://images.example/programme.jpg?credential=secret"/>
                <new/>
              </programme>
            </tv>
        """.trimIndent()
        val sink = RecordingXmltvSink()
        val stream = CloseRecordingInputStream(xml.toByteArray())

        val report = StreamingXmltvParser().parse(stream, sink)

        assertThat(report.channelCount).isEqualTo(1)
        assertThat(report.programmeCount).isEqualTo(1)
        assertThat(report.warningCount).isEqualTo(0)
        assertThat(stream.closed).isFalse()

        val channel = sink.channels.single()
        assertThat(channel.externalId).isEqualTo("news.world")
        assertThat(channel.displayNames.map(XmltvText::value))
            .containsExactly("News World", "Новости мира").inOrder()
        assertThat(channel.icons.single().width).isEqualTo(320)
        assertThat(channel.urls).containsExactly("https://provider.example/channel/news.world")

        val programme = sink.programmes.single()
        assertThat(programme.externalChannelId).isEqualTo("news.world")
        assertThat((programme.start as XmltvTimestamp.Resolved).instant)
            .isEqualTo(Instant.parse("2026-10-24T23:30:00Z"))
        assertThat(programme.titles.single().value).isEqualTo("Private programme title")
        assertThat(programme.categories.map(XmltvText::value))
            .containsExactly("News", "Новости").inOrder()
        assertThat(programme.credits.single().role).isEqualTo(XmltvCreditRole.Director)
        assertThat(programme.isNew).isTrue()

        val diagnostics = listOf(channel, programme, channel.icons.single(), programme.titles.single())
            .joinToString(" | ")
        assertThat(diagnostics).doesNotContain("news.world")
        assertThat(diagnostics).doesNotContain("Private")
        assertThat(diagnostics).doesNotContain("token=secret")
        assertThat(diagnostics).doesNotContain("credential=secret")
        assertThat(diagnostics).doesNotContain("provider.example")
    }

    @Test
    fun `normalizes explicit and shortened timestamps without assuming UTC`() {
        val resolved = XmltvTimestampParser.parse("20260730112233 +0530")
        assertThat(resolved).isInstanceOf(XmltvTimestamp.Resolved::class.java)
        resolved as XmltvTimestamp.Resolved
        assertThat(resolved.instant).isEqualTo(Instant.parse("2026-07-30T05:52:33Z"))
        assertThat(resolved.precision).isEqualTo(XmltvTimestampPrecision.Second)

        val shortened = XmltvTimestampParser.parse("202607")
        assertThat(shortened).isInstanceOf(XmltvTimestamp.Unresolved::class.java)
        shortened as XmltvTimestamp.Unresolved
        assertThat(shortened.localDateTime.toString()).isEqualTo("2026-07-01T00:00")
        assertThat(shortened.precision).isEqualTo(XmltvTimestampPrecision.Month)
        assertThat(shortened.inferredComponents).isTrue()

        assertThat(XmltvTimestampParser.parse("20261301120000 +0000")).isNull()
        assertThat(XmltvTimestampParser.parse("20260730120000 +1860")).isNull()
        assertThat(XmltvTimestampParser.parse("20260730120000 UTC")).isNull()
    }

    @Test
    fun `rejects doctype and malformed XML without resolving or disclosing external identifiers`() {
        val externalSystemId = "https://attacker.example/private.dtd?credential=secret"
        val doctype = """
            <?xml version="1.0"?>
            <!DOCTYPE tv SYSTEM="$externalSystemId">
            <tv/>
        """.trimIndent()

        val doctypeFailure = assertThrows(XmltvParseException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    ByteArrayInputStream(doctype.toByteArray()),
                    RecordingXmltvSink(),
                )
            }
        }
        assertThat(doctypeFailure.reason).isEqualTo(XmltvParseFailureReason.ForbiddenDoctype)
        assertThat(doctypeFailure.message).doesNotContain("attacker.example")
        assertThat(doctypeFailure.message).doesNotContain("credential=secret")
        assertThat(doctypeFailure.cause).isNull()
        assertThat(doctypeFailure.renderedStackTrace()).doesNotContain("attacker.example")
        assertThat(doctypeFailure.renderedStackTrace()).doesNotContain("credential=secret")

        val privatePayload = "<tv><channel id=\"private-channel\"><display-name>Private"
        val malformedFailure = assertThrows(XmltvParseException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    ByteArrayInputStream(privatePayload.toByteArray()),
                    RecordingXmltvSink(),
                )
            }
        }
        assertThat(malformedFailure.reason).isEqualTo(XmltvParseFailureReason.MalformedXml)
        assertThat(malformedFailure.message).doesNotContain("private-channel")
        assertThat(malformedFailure.message).doesNotContain("Private")
        assertThat(malformedFailure.cause).isNull()
        assertThat(malformedFailure.renderedStackTrace()).doesNotContain("private-channel")
        assertThat(malformedFailure.renderedStackTrace()).doesNotContain("Private")
    }

    @Test
    fun `enforces independent byte depth element text and record bounds`() {
        val parser = StreamingXmltvParser()

        assertLimitReason(
            parser,
            "<tv><channel id=\"one\"><display-name>One</display-name></channel></tv>",
            XmltvParseLimits(maxInputBytes = 16),
            XmltvParseFailureReason.InputBytesExceeded,
        )
        assertLimitReason(
            parser,
            "<tv><channel id=\"one\"><display-name>One</display-name></channel></tv>",
            XmltvParseLimits(maxDepth = 2),
            XmltvParseFailureReason.DepthExceeded,
        )
        assertLimitReason(
            parser,
            "<tv><channel id=\"one\"><display-name>One</display-name></channel></tv>",
            XmltvParseLimits(maxElements = 2),
            XmltvParseFailureReason.ElementCountExceeded,
        )
        assertLimitReason(
            parser,
            "<tv><channel id=\"one\"><display-name>0123456789</display-name></channel></tv>",
            XmltvParseLimits(maxTextCharactersPerElement = 5),
            XmltvParseFailureReason.TextCharactersExceeded,
        )
        assertLimitReason(
            parser,
            "<tv><channel id=\"one\"/><channel id=\"two\"/></tv>",
            XmltvParseLimits(maxChannels = 1),
            XmltvParseFailureReason.ChannelCountExceeded,
        )
        assertLimitReason(
            parser,
            "<tv><programme channel=\"one\" start=\"20260730120000 +0000\"/>" +
                "<programme channel=\"one\" start=\"20260730130000 +0000\"/></tv>",
            XmltvParseLimits(maxProgrammes = 1),
            XmltvParseFailureReason.ProgrammeCountExceeded,
        )
    }

    @Test
    fun `emits typed record warnings without exposing record values`() = runTest {
        val xml = """
            <tv>
              <channel><display-name>Missing ID</display-name></channel>
              <programme channel="news" start="bad-time"><title>Bad timestamp</title></programme>
              <programme channel="news" start="20260730130000 +0000" stop="20260730120000 +0000"><title>Reverse</title></programme>
            </tv>
        """.trimIndent()
        val sink = RecordingXmltvSink()

        val report = StreamingXmltvParser().parse(ByteArrayInputStream(xml.toByteArray()), sink)

        assertThat(report.channelCount).isEqualTo(0)
        assertThat(report.programmeCount).isEqualTo(0)
        assertThat(sink.warnings.map(XmltvWarning::kind)).containsExactly(
            XmltvWarningKind.MissingChannelId,
            XmltvWarningKind.InvalidTimestamp,
            XmltvWarningKind.StopBeforeStart,
        ).inOrder()
        assertThat(sink.warnings.joinToString()).doesNotContain("Missing ID")
        assertThat(sink.warnings.joinToString()).doesNotContain("Bad timestamp")
        assertThat(sink.warnings.joinToString()).doesNotContain("Reverse")
    }

    @Test
    fun `propagates sink failures unchanged`() {
        val expectedFailure = IllegalStateException("sink-failed-with-private-value")

        val sinkFailure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    ByteArrayInputStream("<tv><channel id=\"one\"/></tv>".toByteArray()),
                    object : XmltvParseSink {
                        override suspend fun onChannel(channel: XmltvChannel) {
                            throw expectedFailure
                        }
                        override suspend fun onProgramme(programme: XmltvProgramme) = Unit
                        override suspend fun onWarning(warning: XmltvWarning) = Unit
                    },
                )
            }
        }

        assertThat(sinkFailure).isSameInstanceAs(expectedFailure)
    }

    @Test
    fun `propagates sink cancellation unchanged`() {
        val expectedCancellation = CancellationException("expected cancellation")

        val cancellation = assertThrows(CancellationException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    ByteArrayInputStream("<tv><channel id=\"one\"/></tv>".toByteArray()),
                    object : XmltvParseSink {
                        override suspend fun onChannel(channel: XmltvChannel) {
                            throw expectedCancellation
                        }
                        override suspend fun onProgramme(programme: XmltvProgramme) = Unit
                        override suspend fun onWarning(warning: XmltvWarning) = Unit
                    },
                )
            }
        }

        assertThat(cancellation).isSameInstanceAs(expectedCancellation)
    }

    private fun assertLimitReason(
        parser: StreamingXmltvParser,
        xml: String,
        limits: XmltvParseLimits,
        expected: XmltvParseFailureReason,
    ) {
        val failure = assertThrows(XmltvParseException::class.java) {
            runBlocking {
                parser.parse(ByteArrayInputStream(xml.toByteArray()), RecordingXmltvSink(), limits)
            }
        }
        assertThat(failure.reason).isEqualTo(expected)
    }
}

private fun Throwable.renderedStackTrace(): String = StringWriter().also { output ->
    printStackTrace(PrintWriter(output))
}.toString()

private class RecordingXmltvSink : XmltvParseSink {
    val channels = mutableListOf<XmltvChannel>()
    val programmes = mutableListOf<XmltvProgramme>()
    val warnings = mutableListOf<XmltvWarning>()

    override suspend fun onChannel(channel: XmltvChannel) { channels += channel }
    override suspend fun onProgramme(programme: XmltvProgramme) { programmes += programme }
    override suspend fun onWarning(warning: XmltvWarning) { warnings += warning }
}

private class CloseRecordingInputStream(bytes: ByteArray) : InputStream() {
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

package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingXmltvParserStructuralBoundsTest {
    @Test
    fun `limits text in unsupported extension elements`() {
        val failure = assertThrows(XmltvParseException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    input = ByteArrayInputStream(
                        "<tv><provider-extension>0123456789</provider-extension></tv>".toByteArray(),
                    ),
                    sink = EmptyXmltvSink,
                    limits = XmltvParseLimits(maxTextCharactersPerElement = 5),
                )
            }
        }

        assertThat(failure.reason).isEqualTo(XmltvParseFailureReason.TextCharactersExceeded)
    }

    @Test
    fun `limits attributes before record-specific parsing`() {
        val oversizedAttribute = assertThrows(XmltvParseException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    input = ByteArrayInputStream(
                        "<tv generator-info-name=\"0123456789\"/>".toByteArray(),
                    ),
                    sink = EmptyXmltvSink,
                    limits = XmltvParseLimits(
                        maxTextCharactersPerElement = 64,
                        maxStringCharacters = 5,
                    ),
                )
            }
        }
        assertThat(oversizedAttribute.reason)
            .isEqualTo(XmltvParseFailureReason.TextCharactersExceeded)

        val excessiveAttributes = assertThrows(XmltvParseException::class.java) {
            runBlocking {
                StreamingXmltvParser().parse(
                    input = ByteArrayInputStream(
                        "<tv first=\"1\" second=\"2\"/>".toByteArray(),
                    ),
                    sink = EmptyXmltvSink,
                    limits = XmltvParseLimits(maxAttributesPerElement = 1),
                )
            }
        }
        assertThat(excessiveAttributes.reason)
            .isEqualTo(XmltvParseFailureReason.AttributeCountExceeded)
    }
}

private object EmptyXmltvSink : XmltvParseSink {
    override suspend fun onChannel(channel: XmltvChannel) = Unit
    override suspend fun onProgramme(programme: XmltvProgramme) = Unit
    override suspend fun onWarning(warning: XmltvWarning) = Unit
}

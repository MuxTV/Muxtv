package app.muxtv.testing.iptv

import app.muxtv.catalog.ingest.StreamingXmltvParser
import app.muxtv.catalog.ingest.XmltvChannel
import app.muxtv.catalog.ingest.XmltvParseSink
import app.muxtv.catalog.ingest.XmltvProgramme
import app.muxtv.catalog.ingest.XmltvWarning
import app.muxtv.catalog.ingest.XmltvWarningKind
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test

class XmltvStarterFixtureConsumerContractTest {
    @Test
    fun `production parser consumes canonical XMLTV starter fixtures`() = runBlocking {
        val fixtures = IptvStarterFixtureCatalog.all.filter { it.kind == IptvStarterFixtureKind.XMLTV }

        assertThat(fixtures.map(IptvStarterFixture::id)).containsExactly(
            "xmltv-dst-unicode",
            "xmltv-missing-channel-reference",
            "xmltv-malformed-timestamp",
        ).inOrder()

        fixtures.forEach { fixture ->
            val expectation = fixture.expectation as IptvStarterFixtureExpectation.XmlTv
            val sink = RecordingFixtureSink()
            val report = StreamingXmltvParser().parse(
                input = ByteArrayInputStream(fixture.utf8Bytes),
                sink = sink,
            )

            assertThat(report.channelCount).isEqualTo(expectation.channelCount)
            assertThat(report.programmeCount)
                .isEqualTo(expectation.programmeCount - expectation.invalidTimestampCount)
            assertThat(sink.warnings.count { it.kind == XmltvWarningKind.InvalidTimestamp })
                .isEqualTo(expectation.invalidTimestampCount)

            when (fixture.id) {
                "xmltv-dst-unicode" -> {
                    assertThat(sink.programmes).hasSize(2)
                    assertThat(sink.programmes.flatMap { it.titles }.map { it.value })
                        .containsExactly("До перехода", "После перехода").inOrder()
                }
                "xmltv-missing-channel-reference" -> {
                    assertThat(sink.programmes.single().externalChannelId).isEqualTo("missing.channel")
                    assertThat(sink.warnings).isEmpty()
                }
                "xmltv-malformed-timestamp" -> {
                    assertThat(sink.programmes).isEmpty()
                    assertThat(sink.warnings.map(XmltvWarning::kind))
                        .containsExactly(XmltvWarningKind.InvalidTimestamp)
                }
                else -> error("Unexpected canonical XMLTV fixture.")
            }
        }
    }
}

private class RecordingFixtureSink : XmltvParseSink {
    val channels = mutableListOf<XmltvChannel>()
    val programmes = mutableListOf<XmltvProgramme>()
    val warnings = mutableListOf<XmltvWarning>()

    override suspend fun onChannel(channel: XmltvChannel) {
        channels += channel
    }

    override suspend fun onProgramme(programme: XmltvProgramme) {
        programmes += programme
    }

    override suspend fun onWarning(warning: XmltvWarning) {
        warnings += warning
    }
}

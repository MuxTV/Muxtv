package app.muxtv.benchmark

import app.muxtv.catalog.ingest.StreamingXmltvParser
import app.muxtv.catalog.ingest.XmltvChannel
import app.muxtv.catalog.ingest.XmltvParseSink
import app.muxtv.catalog.ingest.XmltvProgramme
import app.muxtv.catalog.ingest.XmltvWarning
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class StreamingXmltvParserBenchmark {
    private lateinit var payload: ByteArray
    private val parser = StreamingXmltvParser()
    private val sink = CountingSink()

    @Setup(Level.Trial)
    fun setup() {
        payload = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<tv>")
            repeat(500) { index ->
                appendLine("<channel id=\"id-$index\"><display-name>Channel $index</display-name></channel>")
                appendLine(
                    "<programme channel=\"id-$index\" start=\"20260809120000 +0500\" " +
                        "stop=\"20260809123000 +0500\"><title>Programme $index</title></programme>",
                )
            }
            appendLine("</tv>")
        }.encodeToByteArray()
    }

    @Benchmark
    fun parseFiveHundredChannelsAndProgrammes(): Int = runBlocking {
        sink.reset()
        parser.parse(ByteArrayInputStream(payload), sink)
        sink.count
    }

    private class CountingSink : XmltvParseSink {
        var count: Int = 0
            private set

        override suspend fun onChannel(channel: XmltvChannel) {
            count += 1
        }

        override suspend fun onProgramme(programme: XmltvProgramme) {
            count += 1
        }

        override suspend fun onWarning(warning: XmltvWarning) = Unit

        fun reset() {
            count = 0
        }
    }
}

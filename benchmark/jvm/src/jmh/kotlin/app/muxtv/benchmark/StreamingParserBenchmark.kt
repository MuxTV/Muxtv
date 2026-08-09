package app.muxtv.benchmark

import app.muxtv.catalog.ingest.M3uEntry
import app.muxtv.catalog.ingest.M3uParseSink
import app.muxtv.catalog.ingest.StreamingM3uParser
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
open class StreamingParserBenchmark {
    private lateinit var payload: ByteArray
    private val parser = StreamingM3uParser()
    private val sink = CountingSink()

    @Setup(Level.Trial)
    fun setup() {
        payload = buildString {
            appendLine("#EXTM3U")
            repeat(1_000) { index ->
                appendLine("#EXTINF:-1 tvg-id=\"id-$index\" group-title=\"Benchmark\",Channel $index")
                appendLine("https://fixture.invalid/channel/$index/index.m3u8")
            }
        }.encodeToByteArray()
    }

    @Benchmark
    fun parseOneThousandEntries(): Int = runBlocking {
        sink.reset()
        parser.parse(ByteArrayInputStream(payload), sink)
        sink.count
    }

    private class CountingSink : M3uParseSink {
        var count: Int = 0
            private set

        override suspend fun onEntry(entry: M3uEntry) {
            count += 1
        }

        fun reset() {
            count = 0
        }
    }
}

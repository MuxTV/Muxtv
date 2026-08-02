package app.muxtv.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ingest.M3uEntry
import app.muxtv.catalog.ingest.M3uParseLimits
import app.muxtv.catalog.ingest.M3uParseSink
import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.catalog.ingest.XmltvTimestampParser
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreAllocationBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val m3uParser = StreamingM3uParser()
    private val m3uBytes = deterministicM3u(entryCount = M3U_ENTRY_COUNT)
    private val timestamps = deterministicTimestamps(count = TIMESTAMP_BATCH_SIZE)

    @Test
    fun m3uParseSmall1k() {
        var parsedEntries = 0

        benchmarkRule.measureRepeated {
            val report = runBlocking {
                m3uParser.parse(
                    input = ByteArrayInputStream(m3uBytes),
                    sink = NoRetentionM3uSink,
                    limits = M3uParseLimits(maxEntries = M3U_ENTRY_COUNT + 1),
                )
            }
            parsedEntries = report.parsedEntries
        }

        check(parsedEntries == M3U_ENTRY_COUNT)
    }

    @Test
    fun xmltvTimestampBatch() {
        var parsedTimestamps = 0

        benchmarkRule.measureRepeated {
            var parsed = 0
            for (raw in timestamps) {
                if (XmltvTimestampParser.parse(raw) != null) parsed++
            }
            parsedTimestamps = parsed
        }

        check(parsedTimestamps == timestamps.size)
    }

    private companion object {
        const val M3U_ENTRY_COUNT = 1_000
        const val TIMESTAMP_BATCH_SIZE = 1_000

        fun deterministicM3u(entryCount: Int): ByteArray = buildString(entryCount * 150) {
            append("#EXTM3U\n")
            repeat(entryCount) { index ->
                append("#EXTINF:-1 tvg-id=\"channel-")
                append(index)
                append("\" tvg-name=\"Channel ")
                append(index)
                append("\" group-title=\"Group ")
                append(index % 16)
                append("\" tvg-chno=\"")
                append(index + 1)
                append("\",Channel ")
                append(index)
                append('\n')
                append("https://stream.example/live/")
                append(index)
                append("/index.m3u8\n")
            }
        }.encodeToByteArray()

        fun deterministicTimestamps(count: Int): List<String> = List(count) { index ->
            val day = index % 28 + 1
            val hour = index % 24
            val minute = index % 60
            val second = index * 7 % 60
            buildString(20) {
                append("202608")
                appendTwoDigits(day)
                appendTwoDigits(hour)
                appendTwoDigits(minute)
                appendTwoDigits(second)
                append(" +0300")
            }
        }

        fun StringBuilder.appendTwoDigits(value: Int) {
            if (value < 10) append('0')
            append(value)
        }
    }
}

private object NoRetentionM3uSink : M3uParseSink {
    override suspend fun onEntry(entry: M3uEntry) = Unit
}

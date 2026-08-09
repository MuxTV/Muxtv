package app.muxtv.benchmark

import app.muxtv.catalog.ChannelSearchQuery
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class SearchNormalizationBenchmark {
    private val input = "  Первый\tканал   новости  HD  "

    @Benchmark
    fun normalizeAndValidate(): String = ChannelSearchQuery(
        profileId = "primary",
        text = input,
        nowEpochMillis = 1_700_000_000_000,
    ).normalizedText
}

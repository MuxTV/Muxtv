package app.muxtv.benchmark

import app.muxtv.benchmark.epg.EpgBenchmarkMatch
import app.muxtv.benchmark.epg.EpgBenchmarkQuery
import app.muxtv.benchmark.epg.HybridEpgMatcher
import app.muxtv.benchmark.epg.MuxBaselineEpgMatcher
import app.muxtv.benchmark.epg.OwnTvReferenceEpgMatcher
import app.muxtv.benchmark.epgmatch.EpgMatchAdversarialCorpus
import app.muxtv.benchmark.epgmatch.EpgMatchThresholdSweep
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime, Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class EpgMatchingBenchmark {
    private lateinit var baseline: List<PreparedBenchmarkCase<MuxBaselineEpgMatcher>>
    private lateinit var hybrid: List<PreparedBenchmarkCase<HybridEpgMatcher>>
    private lateinit var reference: List<PreparedBenchmarkCase<OwnTvReferenceEpgMatcher>>

    @Setup(Level.Trial)
    fun setup() {
        val cases = EpgMatchAdversarialCorpus.cases
        val selected = EpgMatchThresholdSweep.run(EpgMatchAdversarialCorpus).selectedThresholds
        baseline = cases.map { case ->
            PreparedBenchmarkCase(
                matcher = MuxBaselineEpgMatcher(case.candidates, case.query.providerSourceId),
                query = case.query,
            )
        }
        hybrid = cases.map { case ->
            PreparedBenchmarkCase(
                matcher = HybridEpgMatcher(
                    candidates = case.candidates,
                    providerSourceId = case.query.providerSourceId,
                    thresholds = selected.toMatcherThresholdsForJmh(),
                ),
                query = case.query,
            )
        }
        reference = cases.map { case ->
            PreparedBenchmarkCase(
                matcher = OwnTvReferenceEpgMatcher(case.candidates, case.query.providerSourceId),
                query = case.query,
            )
        }
    }

    @Benchmark
    fun variantA(): Int = consumeBaseline()

    @Benchmark
    fun variantB(): Int = consumeHybrid()

    @Benchmark
    fun variantC(): Int = consumeReference()

    private fun consumeBaseline(): Int {
        var checksum = 1
        baseline.forEach { prepared ->
            checksum = checksum * 31 + prepared.matcher.match(prepared.query).checksum()
        }
        return checksum
    }

    private fun consumeHybrid(): Int {
        var checksum = 1
        hybrid.forEach { prepared ->
            checksum = checksum * 31 + prepared.matcher.match(prepared.query).checksum()
        }
        return checksum
    }

    private fun consumeReference(): Int {
        var checksum = 1
        reference.forEach { prepared ->
            checksum = checksum * 31 + prepared.matcher.match(prepared.query).checksum()
        }
        return checksum
    }
}

private data class PreparedBenchmarkCase<T>(
    val matcher: T,
    val query: EpgBenchmarkQuery,
)

private fun EpgBenchmarkMatch.checksum(): Int {
    var result = decision.ordinal * 31 + reason.ordinal
    result = result * 31 + (canonicalChannelId?.hashCode() ?: 0)
    ranking.forEach { ranked ->
        result = result * 31 + ranked.canonicalChannelId.hashCode()
        result = result * 31 + ranked.score.toBits().hashCode()
    }
    return result
}

private fun app.muxtv.benchmark.epgmatch.EpgHybridThresholds.toMatcherThresholdsForJmh() =
    app.muxtv.benchmark.epg.HybridThresholds(
        autoThreshold = autoThreshold,
        reviewThreshold = reviewThreshold,
        ambiguityMargin = autoMargin,
    )

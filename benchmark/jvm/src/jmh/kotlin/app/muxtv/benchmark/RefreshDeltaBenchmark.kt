package app.muxtv.benchmark

import app.muxtv.benchmark.refreshdelta.RefreshDeltaCorpus
import app.muxtv.benchmark.refreshdelta.RefreshDeltaItem
import app.muxtv.benchmark.refreshdelta.RefreshDeltaPrototype
import app.muxtv.benchmark.refreshdelta.RefreshDeltaResult
import app.muxtv.benchmark.refreshdelta.RefreshDeltaVariant
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class RefreshDeltaBenchmark {
    @Benchmark
    fun currentMuxTvFullRevision(state: RefreshDeltaBenchmarkState): RefreshDeltaResult =
        state.prototype.evaluate(
            variant = RefreshDeltaVariant.A_CURRENT_MUXTV,
            previous = state.previous,
            incoming = state.incoming,
        )

    @Benchmark
    fun immutableCowCandidate(state: RefreshDeltaBenchmarkState): RefreshDeltaResult =
        state.prototype.evaluate(
            variant = RefreshDeltaVariant.B_IMMUTABLE_COW,
            previous = state.previous,
            incoming = state.incoming,
        )

    @Benchmark
    fun ownTvStableUpsertReference(state: RefreshDeltaBenchmarkState): RefreshDeltaResult =
        state.prototype.evaluate(
            variant = RefreshDeltaVariant.C_OWNTV_REFERENCE,
            previous = state.previous,
            incoming = state.incoming,
        )
}

@State(Scope.Benchmark)
open class RefreshDeltaBenchmarkState {
    @Param("1000", "10000", "50000")
    var size: Int = 1_000

    @Param("UNCHANGED", "ONE_PERCENT", "TEN_PERCENT", "FULL", "REORDER", "REMOVE_TEN_PERCENT", "TOKEN_CHURN")
    lateinit var scenario: String

    lateinit var previous: List<RefreshDeltaItem>
    lateinit var incoming: List<RefreshDeltaItem>
    val prototype = RefreshDeltaPrototype()

    @Setup(Level.Trial)
    fun prepare() {
        previous = RefreshDeltaCorpus.baseline(size = size, seed = FIXTURE_SEED)
        incoming = when (scenario) {
            "UNCHANGED" -> previous
            "ONE_PERCENT" -> RefreshDeltaCorpus.contentDelta(previous, changedPercent = 1)
            "TEN_PERCENT" -> RefreshDeltaCorpus.contentDelta(previous, changedPercent = 10)
            "FULL" -> RefreshDeltaCorpus.contentDelta(previous, changedPercent = 100)
            "REORDER" -> RefreshDeltaCorpus.reverseOrder(previous)
            "REMOVE_TEN_PERCENT" -> RefreshDeltaCorpus.removeTail(previous, count = size / 10)
            "TOKEN_CHURN" -> RefreshDeltaCorpus.tokenizedLocatorChurn(previous)
            else -> error("Unsupported C03 refresh delta scenario.")
        }
    }

    private companion object {
        const val FIXTURE_SEED = 348_003L
    }
}

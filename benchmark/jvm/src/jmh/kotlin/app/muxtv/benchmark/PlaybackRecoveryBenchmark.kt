package app.muxtv.benchmark

import app.muxtv.common.CanonicalChannelId
import app.muxtv.common.StreamVariantId
import app.muxtv.player.PlaybackRecoveryBudget
import app.muxtv.player.PlaybackRecoveryCandidate
import app.muxtv.player.PlaybackRecoveryDisposition
import app.muxtv.player.PlaybackRecoveryPlan
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
open class PlaybackRecoveryBenchmark {
    private val channelId = CanonicalChannelId("benchmark-channel")
    private val candidates = (1..6).map { index ->
        PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-$index"))
    }
    private val budget = PlaybackRecoveryBudget(maxAttempts = 3, maxRecoveryDurationMillis = 20_000)
    private val plan = PlaybackRecoveryPlan.create(channelId, candidates, candidates[2].variantId, budget)

    @Benchmark
    fun candidateLookup(): PlaybackRecoveryCandidate? = plan.candidateAt(1, 1_000)

    @Benchmark
    fun candidateAfterFailure(): PlaybackRecoveryCandidate? = plan.candidateAfterFailure(
        failedAttemptIndex = 0,
        elapsedRecoveryMillis = 1_000,
        disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
    )

    @Benchmark
    fun createPreferredFirstPlan(): PlaybackRecoveryPlan = PlaybackRecoveryPlan.create(
        canonicalChannelId = channelId,
        candidates = candidates,
        preferredVariantId = candidates[2].variantId,
        budget = budget,
    )
}

package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.player.PlaybackStartRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecoveryOrchestratorTest {
    @Test
    fun `preferred candidate is resolved first and only the current candidate is installed`() {
        val orchestrator = orchestrator(now = { 0L })
        val first = candidate("variant-first", order = 0)
        val preferred = candidate("variant-preferred", order = 1)

        val resolve = orchestrator.start(request(preferred.variantId), listOf(first, preferred))

        assertThat(resolve).isEqualTo(
            PlaybackRecoveryAction.ResolveCandidate(
                generation = resolve.generation(),
                candidate = preferred,
                attempt = 0,
            ),
        )

        val install = orchestrator.onCandidateResolved(
            generation = resolve.generation(),
            candidate = preferred,
            resolution = PlaybackVariantResolution.Ready(
                resolvedRequest(preferred.variantId),
            ),
        )

        assertThat(install).isInstanceOf(PlaybackRecoveryAction.Install::class.java)
        assertThat((install as PlaybackRecoveryAction.Install).request.variantId)
            .isEqualTo(preferred.variantId)
    }

    @Test
    fun `candidate error advances once and stale callbacks cannot complete the next candidate`() {
        val orchestrator = orchestrator(now = { 0L })
        val first = candidate("variant-first", order = 0)
        val second = candidate("variant-second", order = 1)
        val start = orchestrator.start(request(), listOf(first, second))
        val generation = start.generation()

        orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(resolvedRequest(first.variantId)),
        )
        val next = orchestrator.onPlayerError(
            generation = generation,
            candidate = first,
        )

        assertThat(next).isEqualTo(
            PlaybackRecoveryAction.ResolveCandidate(
                generation = generation,
                candidate = second,
                attempt = 1,
            ),
        )
        assertThat(
            orchestrator.onRenderedFirstFrame(generation, first),
        ).isEqualTo(PlaybackRecoveryAction.Ignored)
    }

    @Test
    fun `first frame completes recovery and duplicate or late callbacks are inert`() {
        val orchestrator = orchestrator(now = { 0L })
        val first = candidate("variant-first", order = 0)
        val start = orchestrator.start(request(), listOf(first))
        val generation = start.generation()

        orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(resolvedRequest(first.variantId)),
        )

        assertThat(orchestrator.onRenderedFirstFrame(generation, first))
            .isEqualTo(PlaybackRecoveryAction.Succeeded(generation, first))
        assertThat(orchestrator.onRenderedFirstFrame(generation, first))
            .isEqualTo(PlaybackRecoveryAction.Ignored)
        assertThat(orchestrator.onPlayerError(generation, first))
            .isEqualTo(PlaybackRecoveryAction.Ignored)
    }

    @Test
    fun `new request and cancellation invalidate old generation callbacks`() {
        val now = MutableTime()
        val orchestrator = orchestrator(now::get)
        val first = candidate("variant-first", order = 0)
        val oldStart = orchestrator.start(request(), listOf(first))
        val oldGeneration = oldStart.generation()
        val newStart = orchestrator.start(request("variant-new"), listOf(candidate("variant-new", 0)))

        assertThat(orchestrator.onRenderedFirstFrame(oldGeneration, first))
            .isEqualTo(PlaybackRecoveryAction.Ignored)
        orchestrator.cancel()
        assertThat(orchestrator.onPlayerError(newStart.generation(), candidate("variant-new", 0)))
            .isEqualTo(PlaybackRecoveryAction.Ignored)
    }

    @Test
    fun `attempt and deadline budgets stop recovery`() {
        val now = MutableTime()
        val orchestrator = orchestrator(now::get)
        val candidates = (0..3).map { index -> candidate("variant-$index", index) }
        val start = orchestrator.start(request(), candidates)
        val generation = start.generation()

        var current = candidates.first()
        repeat(2) { attempt ->
            orchestrator.onCandidateResolved(
                generation = generation,
                candidate = current,
                resolution = PlaybackVariantResolution.Ready(resolvedRequest(current.variantId)),
            )
            val next = orchestrator.onPlayerError(generation, current)
            assertThat(next).isInstanceOf(PlaybackRecoveryAction.ResolveCandidate::class.java)
            current = (next as PlaybackRecoveryAction.ResolveCandidate).candidate
            assertThat(next.attempt).isEqualTo(attempt + 1)
        }

        now.value = 20_000L
        assertThat(
            orchestrator.onCandidateResolved(
                generation = generation,
                candidate = current,
                resolution = PlaybackVariantResolution.Ready(resolvedRequest(current.variantId)),
            ),
        )
            .isEqualTo(
                PlaybackRecoveryAction.Failed(
                    generation,
                    PlaybackRecoveryFailure.BudgetExhausted,
                ),
            )
    }

    @Test
    fun `first frame at the deadline cannot win the timeout race`() {
        val now = MutableTime()
        val orchestrator = orchestrator(now::get)
        val first = candidate("variant-first", 0)
        val generation = orchestrator.start(request(), listOf(first)).generation()
        orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(resolvedRequest(first.variantId)),
        )

        now.value = 20_000L

        assertThat(orchestrator.onRenderedFirstFrame(generation, first)).isEqualTo(
            PlaybackRecoveryAction.Failed(
                generation,
                PlaybackRecoveryFailure.BudgetExhausted,
            ),
        )
    }

    @Test
    fun `resolver cannot install a foreign channel or variant`() {
        val orchestrator = orchestrator(now = { 0L })
        val first = candidate("variant-first", 0)
        val second = candidate("variant-second", 1)
        val generation = orchestrator.start(request(), listOf(first, second)).generation()

        val next = orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(
                resolvedRequest(first.variantId).copy(channelId = "channel-foreign"),
            ),
        )

        assertThat(next).isEqualTo(
            PlaybackRecoveryAction.ResolveCandidate(
                generation = generation,
                candidate = second,
                attempt = 1,
            ),
        )
    }

    private fun orchestrator(now: () -> Long): PlaybackRecoveryOrchestrator =
        PlaybackRecoveryOrchestrator(
            elapsedRealtimeMillis = now,
            maxAttempts = 3,
            maxRecoveryDurationMillis = 20_000L,
        )

    private fun request(preferredVariantId: String? = null) = PlaybackStartRequest(
        profileId = "profile-main",
        channelId = "channel-news",
        preferredVariantId = preferredVariantId,
    )

    private fun candidate(variantId: String, order: Int) = PlaybackCandidateIdentity(
        channelId = "channel-news",
        variantId = variantId,
    ).also { require(order >= 0) }

    private fun resolvedRequest(variantId: String) = app.muxtv.catalog.ResolvedPlaybackRequest(
        channelId = "channel-news",
        variantId = variantId,
        locator = "https://stream.example/live.m3u8",
        requestHeaders = emptyMap(),
        insecureHttpApproved = false,
    )

    private fun PlaybackRecoveryAction.generation() = when (this) {
        is PlaybackRecoveryAction.ResolveCandidate -> generation
        is PlaybackRecoveryAction.Install -> generation
        is PlaybackRecoveryAction.ApprovalRequired -> generation
        is PlaybackRecoveryAction.Succeeded -> generation
        is PlaybackRecoveryAction.Failed -> generation
        PlaybackRecoveryAction.Cancelled,
        PlaybackRecoveryAction.Ignored,
        -> error("Action does not expose a generation")
    }

    private class MutableTime(var value: Long = 0L) {
        fun get(): Long = value
    }
}

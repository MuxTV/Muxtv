package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.player.PlaybackStartRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecoveryDispositionTest {
    @Test
    fun `stop recovery is terminal and never resolves the next candidate`() {
        val orchestrator = orchestrator()
        val first = candidate("variant-first")
        val second = candidate("variant-second")
        val start = orchestrator.start(request(), listOf(first, second))
        val generation = start.generation()

        orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(resolvedRequest(first.variantId)),
        )

        val stopped = orchestrator.onPlayerError(
            generation = generation,
            candidate = first,
            disposition = PlaybackRecoveryDisposition.STOP_RECOVERY,
        )

        assertThat(stopped).isEqualTo(
            PlaybackRecoveryAction.Failed(
                generation = generation,
                failure = PlaybackRecoveryFailure.CandidatesExhausted,
                attempt = 0,
            ),
        )
        assertThat(
            orchestrator.onPlayerError(
                generation = generation,
                candidate = first,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            ),
        ).isEqualTo(PlaybackRecoveryAction.Ignored)
    }

    @Test
    fun `try next candidate preserves one step bounded advance`() {
        val orchestrator = orchestrator()
        val first = candidate("variant-first")
        val second = candidate("variant-second")
        val generation = orchestrator.start(request(), listOf(first, second)).generation()

        orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(resolvedRequest(first.variantId)),
        )

        assertThat(
            orchestrator.onPlayerError(
                generation = generation,
                candidate = first,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            ),
        ).isEqualTo(
            PlaybackRecoveryAction.ResolveCandidate(
                generation = generation,
                candidate = second,
                attempt = 1,
            ),
        )
    }

    private fun orchestrator() = PlaybackRecoveryOrchestrator(
        elapsedRealtimeMillis = { 0L },
        maxAttempts = 3,
        maxRecoveryDurationMillis = 20_000L,
    )

    private fun request() = PlaybackStartRequest(
        profileId = "profile-main",
        channelId = "channel-news",
    )

    private fun candidate(variantId: String) = PlaybackCandidateIdentity(
        channelId = "channel-news",
        variantId = variantId,
    )

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
}

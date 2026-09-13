package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.player.PlaybackStartRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@AndroidXOptIn(UnstableApi::class)
class C11TypedRecoveryEvidenceTest {
    @Test
    fun `deterministic corpus accepts narrow B and rejects broad C`() {
        val summaries = Media3RecoveryPolicyVariant.entries.associateWith(::runCorpus)
        summaries.forEach { (variant, summary) ->
            println(
                "C11_EVIDENCE variant=$variant " +
                    "useful_recovery_successes=${summary.usefulRecoverySuccesses} " +
                    "false_stops=${summary.falseStops} " +
                    "candidate_actions=${summary.candidateActions} " +
                    "terminal_extra_actions=${summary.terminalExtraActions}",
            )
        }

        val a = requireNotNull(summaries[Media3RecoveryPolicyVariant.A_CURRENT_GENERIC_NEXT])
        val b = requireNotNull(summaries[Media3RecoveryPolicyVariant.B_NARROW_TYPED_STOP])
        val c = requireNotNull(summaries[Media3RecoveryPolicyVariant.C_BROAD_RENDER_STOP])

        assertThat(a).isEqualTo(
            EvidenceSummary(
                usefulRecoverySuccesses = 12,
                falseStops = 0,
                candidateActions = 30,
                terminalExtraActions = 2,
            ),
        )
        assertThat(b).isEqualTo(
            EvidenceSummary(
                usefulRecoverySuccesses = 12,
                falseStops = 0,
                candidateActions = 28,
                terminalExtraActions = 0,
            ),
        )
        assertThat(c).isEqualTo(
            EvidenceSummary(
                usefulRecoverySuccesses = 10,
                falseStops = 2,
                candidateActions = 26,
                terminalExtraActions = 0,
            ),
        )

        assertThat(b.usefulRecoverySuccesses).isEqualTo(a.usefulRecoverySuccesses)
        assertThat(b.falseStops).isEqualTo(0)
        assertThat(b.candidateActions).isLessThan(a.candidateActions)
        assertThat(b.terminalExtraActions).isLessThan(a.terminalExtraActions)
        assertThat(c.falseStops).isGreaterThan(0)
    }

    @Test
    fun `deadline remains authoritative even when policy says stop`() {
        var now = 0L
        val orchestrator = orchestrator { now }
        val first = candidate(0)
        val generation = orchestrator.start(request(), listOf(first, candidate(1))).generation()
        orchestrator.onCandidateResolved(
            generation = generation,
            candidate = first,
            resolution = PlaybackVariantResolution.Ready(resolvedRequest(first.variantId)),
        )
        now = DEADLINE_MILLIS

        val action = orchestrator.onPlayerError(
            generation = generation,
            candidate = first,
            disposition = Media3RecoveryDispositionPolicy.disposition(
                variant = Media3RecoveryPolicyVariant.B_NARROW_TYPED_STOP,
                failure = runtimeCheckFailure(),
            ),
        )

        assertThat(action).isEqualTo(
            PlaybackRecoveryAction.Failed(
                generation = generation,
                failure = PlaybackRecoveryFailure.DeadlineExceeded,
                attempt = 0,
            ),
        )
    }

    @Test
    fun `superseded generation is inert for every policy variant`() {
        Media3RecoveryPolicyVariant.entries.forEach { variant ->
            val orchestrator = orchestrator { 0L }
            val oldCandidate = candidate(0)
            val oldGeneration = orchestrator.start(
                request(),
                listOf(oldCandidate, candidate(1)),
            ).generation()
            orchestrator.onCandidateResolved(
                generation = oldGeneration,
                candidate = oldCandidate,
                resolution = PlaybackVariantResolution.Ready(
                    resolvedRequest(oldCandidate.variantId),
                ),
            )

            orchestrator.start(
                request(preferredVariantId = "variant-1"),
                listOf(candidate(1), candidate(2)),
            )

            assertThat(
                orchestrator.onPlayerError(
                    generation = oldGeneration,
                    candidate = oldCandidate,
                    disposition = Media3RecoveryDispositionPolicy.disposition(
                        variant = variant,
                        failure = runtimeCheckFailure(),
                    ),
                ),
            ).isEqualTo(PlaybackRecoveryAction.Ignored)
        }
    }

    private fun runCorpus(variant: Media3RecoveryPolicyVariant): EvidenceSummary {
        val results = CORPUS.map { scenario -> runScenario(variant, scenario) }
        return EvidenceSummary(
            usefulRecoverySuccesses = results.count { it.success && it.candidateActions > 1 },
            falseStops = results.count { it.expectedSuccess && !it.success },
            candidateActions = results.sumOf(ScenarioResult::candidateActions),
            terminalExtraActions = results
                .filter(ScenarioResult::terminalAfterFirstFailure)
                .sumOf { (it.candidateActions - 1).coerceAtLeast(0) },
        )
    }

    private fun runScenario(
        variant: Media3RecoveryPolicyVariant,
        scenario: Scenario,
    ): ScenarioResult {
        val orchestrator = orchestrator { 0L }
        val candidates = scenario.outcomes.indices.map(::candidate)
        var action: PlaybackRecoveryAction = orchestrator.start(request(), candidates)
        var candidateActions = 0

        while (true) {
            when (action) {
                is PlaybackRecoveryAction.ResolveCandidate -> {
                    candidateActions += 1
                    val candidate = action.candidate
                    val install = orchestrator.onCandidateResolved(
                        generation = action.generation,
                        candidate = candidate,
                        resolution = PlaybackVariantResolution.Ready(
                            resolvedRequest(candidate.variantId),
                        ),
                    )
                    check(install is PlaybackRecoveryAction.Install)

                    when (val outcome = scenario.outcomes[action.attempt]) {
                        CandidateOutcome.Success -> {
                            val completed = orchestrator.onRenderedFirstFrame(
                                generation = action.generation,
                                candidate = candidate,
                            )
                            check(completed is PlaybackRecoveryAction.Succeeded)
                            return ScenarioResult(
                                expectedSuccess = scenario.expectedSuccess,
                                success = true,
                                candidateActions = candidateActions,
                                terminalAfterFirstFailure = scenario.terminalAfterFirstFailure,
                            )
                        }

                        is CandidateOutcome.Failure -> {
                            action = orchestrator.onPlayerError(
                                generation = action.generation,
                                candidate = candidate,
                                disposition = Media3RecoveryDispositionPolicy.disposition(
                                    variant = variant,
                                    failure = outcome.failure,
                                ),
                            )
                        }
                    }
                }

                is PlaybackRecoveryAction.Failed ->
                    return ScenarioResult(
                        expectedSuccess = scenario.expectedSuccess,
                        success = false,
                        candidateActions = candidateActions,
                        terminalAfterFirstFailure = scenario.terminalAfterFirstFailure,
                    )

                PlaybackRecoveryAction.Cancelled,
                PlaybackRecoveryAction.Ignored,
                is PlaybackRecoveryAction.ApprovalRequired,
                is PlaybackRecoveryAction.Install,
                is PlaybackRecoveryAction.Succeeded,
                -> error("Unexpected recovery action in C11 corpus: $action")
            }
        }
    }

    private fun orchestrator(now: () -> Long) = PlaybackRecoveryOrchestrator(
        elapsedRealtimeMillis = now,
        maxAttempts = 3,
        maxRecoveryDurationMillis = DEADLINE_MILLIS,
    )

    private fun request(preferredVariantId: String? = null) = PlaybackStartRequest(
        profileId = "profile-c11",
        channelId = CHANNEL_ID,
        preferredVariantId = preferredVariantId,
    )

    private fun candidate(index: Int) = PlaybackCandidateIdentity(
        channelId = CHANNEL_ID,
        variantId = "variant-$index",
    )

    private fun resolvedRequest(variantId: String) = ResolvedPlaybackRequest(
        channelId = CHANNEL_ID,
        variantId = variantId,
        locator = "https://example.invalid/live.m3u8",
        requestHeaders = emptyMap(),
        insecureHttpApproved = false,
    )

    private fun failure(
        category: PlaybackFailureCategory,
        media3ErrorCode: Int = PlaybackException.ERROR_CODE_UNSPECIFIED,
        httpStatusCode: Int? = null,
    ) = CandidateOutcome.Failure(
        Media3Failure(
            category = category,
            httpStatusCode = httpStatusCode,
            media3ErrorCode = media3ErrorCode,
        ),
    )

    private fun runtimeCheckFailure() = Media3Failure(
        category = PlaybackFailureCategory.PLAYER_RENDER,
        media3ErrorCode = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
    )

    private fun PlaybackRecoveryAction.generation(): Long = when (this) {
        is PlaybackRecoveryAction.ResolveCandidate -> generation
        is PlaybackRecoveryAction.Install -> generation
        is PlaybackRecoveryAction.ApprovalRequired -> generation
        is PlaybackRecoveryAction.Succeeded -> generation
        is PlaybackRecoveryAction.Failed -> generation
        PlaybackRecoveryAction.Cancelled,
        PlaybackRecoveryAction.Ignored,
        -> error("Action does not expose a generation")
    }

    private data class EvidenceSummary(
        val usefulRecoverySuccesses: Int,
        val falseStops: Int,
        val candidateActions: Int,
        val terminalExtraActions: Int,
    )

    private data class ScenarioResult(
        val expectedSuccess: Boolean,
        val success: Boolean,
        val candidateActions: Int,
        val terminalAfterFirstFailure: Boolean,
    )

    private data class Scenario(
        val id: String,
        val outcomes: List<CandidateOutcome>,
        val expectedSuccess: Boolean,
        val terminalAfterFirstFailure: Boolean = false,
    )

    private sealed interface CandidateOutcome {
        data object Success : CandidateOutcome
        data class Failure(val failure: Media3Failure) : CandidateOutcome
    }

    private companion object {
        const val CHANNEL_ID = "channel-c11"
        const val DEADLINE_MILLIS = 20_000L

        fun recoverable(
            id: String,
            failure: CandidateOutcome.Failure,
        ) = Scenario(
            id = id,
            outcomes = listOf(failure, CandidateOutcome.Success, failure),
            expectedSuccess = true,
        )

        val CORPUS = listOf(
            recoverable("timeout", failure(PlaybackFailureCategory.TIMEOUT)),
            recoverable("dns", failure(PlaybackFailureCategory.DNS)),
            recoverable("tls", failure(PlaybackFailureCategory.TLS)),
            recoverable("network", failure(PlaybackFailureCategory.NETWORK_UNREACHABLE)),
            recoverable(
                "http-401",
                failure(PlaybackFailureCategory.HTTP_RESPONSE, httpStatusCode = 401),
            ),
            recoverable(
                "http-403",
                failure(PlaybackFailureCategory.HTTP_RESPONSE, httpStatusCode = 403),
            ),
            recoverable("auth-expired", failure(PlaybackFailureCategory.CREDENTIAL_ACCESS)),
            recoverable("manifest", failure(PlaybackFailureCategory.MANIFEST_FORMAT)),
            recoverable("decoder", failure(PlaybackFailureCategory.CODEC_DECODER)),
            recoverable(
                "behind-live-window",
                failure(
                    PlaybackFailureCategory.PLAYER_RENDER,
                    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                ),
            ),
            recoverable(
                "video-frame-processing",
                failure(
                    PlaybackFailureCategory.PLAYER_RENDER,
                    PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
                ),
            ),
            recoverable("unknown", failure(PlaybackFailureCategory.UNKNOWN)),
            Scenario(
                id = "failed-runtime-check-global",
                outcomes = List(3) {
                    failure(
                        PlaybackFailureCategory.PLAYER_RENDER,
                        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                    )
                },
                expectedSuccess = false,
                terminalAfterFirstFailure = true,
            ),
            Scenario(
                id = "all-candidates-timeout",
                outcomes = List(3) { failure(PlaybackFailureCategory.TIMEOUT) },
                expectedSuccess = false,
            ),
        )
    }
}

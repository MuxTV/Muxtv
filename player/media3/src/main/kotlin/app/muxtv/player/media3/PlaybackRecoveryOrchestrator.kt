package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.player.PlaybackStartRequest

internal enum class PlaybackRecoveryFailure {
    NoCandidates,
    AccessUnavailable,
    CandidatesExhausted,
    DeadlineExceeded,
}

internal sealed interface PlaybackRecoveryAction {
    data class ResolveCandidate(
        val generation: Long,
        val candidate: PlaybackCandidateIdentity,
        val attempt: Int,
    ) : PlaybackRecoveryAction

    data class Install(
        val generation: Long,
        val candidate: PlaybackCandidateIdentity,
        val request: ResolvedPlaybackRequest,
        val attempt: Int = 0,
    ) : PlaybackRecoveryAction

    data class ApprovalRequired(
        val generation: Long,
        val candidate: PlaybackCandidateIdentity,
        val displayOrigin: String,
        val attempt: Int = 0,
    ) : PlaybackRecoveryAction

    data class Succeeded(
        val generation: Long,
        val candidate: PlaybackCandidateIdentity,
        val attempt: Int = 0,
    ) : PlaybackRecoveryAction

    data class Failed(
        val generation: Long,
        val failure: PlaybackRecoveryFailure,
        val attempt: Int = 0,
    ) : PlaybackRecoveryAction

    data object Cancelled : PlaybackRecoveryAction
    data object Ignored : PlaybackRecoveryAction
}

internal class PlaybackRecoveryOrchestrator(
    private val elapsedRealtimeMillis: () -> Long,
    private val maxAttempts: Int,
    private val maxRecoveryDurationMillis: Long,
) {
    private var nextGeneration = 1L
    private var active: Active? = null

    init {
        require(maxAttempts > 0)
        require(maxRecoveryDurationMillis > 0)
    }

    fun start(
        request: PlaybackStartRequest,
        candidates: List<PlaybackCandidateIdentity>,
        deadlineAtMillis: Long = elapsedRealtimeMillis() + maxRecoveryDurationMillis,
    ): PlaybackRecoveryAction {
        val generation = nextGeneration++
        if (elapsedRealtimeMillis() >= deadlineAtMillis) {
            active = null
            return PlaybackRecoveryAction.Failed(
                generation = generation,
                failure = PlaybackRecoveryFailure.DeadlineExceeded,
            )
        }
        val ordered = candidates
            .also { entries -> require(entries.all { it.channelId == request.channelId }) }
            .distinctBy(PlaybackCandidateIdentity::variantId)
            .let { entries ->
                val preferredIndex = entries.indexOfFirst {
                    it.variantId == request.preferredVariantId
                }
                if (preferredIndex <= 0) entries else buildList(entries.size) {
                    add(entries[preferredIndex])
                    entries.forEachIndexed { index, candidate ->
                        if (index != preferredIndex) add(candidate)
                    }
                }
            }
            .take(maxAttempts)
        if (ordered.isEmpty()) {
            active = null
            return PlaybackRecoveryAction.Failed(
                generation = generation,
                failure = PlaybackRecoveryFailure.NoCandidates,
            )
        }
        active = Active(
            generation = generation,
            deadlineAtMillis = deadlineAtMillis,
            candidates = ordered,
            attempt = 0,
            phase = Phase.Resolving,
        )
        return active!!.resolveAction()
    }

    fun onCandidateResolved(
        generation: Long,
        candidate: PlaybackCandidateIdentity,
        resolution: PlaybackVariantResolution?,
    ): PlaybackRecoveryAction {
        val state = current(generation, candidate, Phase.Resolving)
            ?: return PlaybackRecoveryAction.Ignored
        if (isExpired(state)) return fail(state, PlaybackRecoveryFailure.DeadlineExceeded)
        return when (resolution) {
            is PlaybackVariantResolution.Ready -> {
                if (!resolution.request.matches(candidate)) {
                    return advance(state, PlaybackRecoveryFailure.AccessUnavailable)
                }
                state.phase = Phase.Installed
                PlaybackRecoveryAction.Install(
                    generation = generation,
                    candidate = candidate,
                    request = resolution.request,
                    attempt = state.attempt,
                )
            }
            is PlaybackVariantResolution.InsecureTransportApprovalRequired -> {
                if (resolution.channelId != candidate.channelId ||
                    resolution.variantId != candidate.variantId
                ) {
                    return advance(state, PlaybackRecoveryFailure.AccessUnavailable)
                }
                active = null
                PlaybackRecoveryAction.ApprovalRequired(
                    generation = generation,
                    candidate = candidate,
                    displayOrigin = resolution.displayOrigin,
                    attempt = state.attempt,
                )
            }
            is PlaybackVariantResolution.AccessUnavailable,
            null,
            -> advance(state, PlaybackRecoveryFailure.AccessUnavailable)
        }
    }

    fun onPlayerError(
        generation: Long,
        candidate: PlaybackCandidateIdentity,
    ): PlaybackRecoveryAction {
        val state = current(generation, candidate, Phase.Installed)
            ?: return PlaybackRecoveryAction.Ignored
        return advance(state, PlaybackRecoveryFailure.CandidatesExhausted)
    }

    fun onRenderedFirstFrame(
        generation: Long,
        candidate: PlaybackCandidateIdentity,
    ): PlaybackRecoveryAction {
        val state = current(generation, candidate, Phase.Installed)
            ?: return PlaybackRecoveryAction.Ignored
        if (isExpired(state)) return fail(state, PlaybackRecoveryFailure.DeadlineExceeded)
        active = null
        return PlaybackRecoveryAction.Succeeded(generation, candidate, state.attempt)
    }

    fun cancel(): PlaybackRecoveryAction {
        if (active == null) return PlaybackRecoveryAction.Ignored
        active = null
        return PlaybackRecoveryAction.Cancelled
    }

    private fun current(
        generation: Long,
        candidate: PlaybackCandidateIdentity,
        phase: Phase,
    ): Active? = active?.takeIf { state ->
        state.generation == generation &&
            state.phase == phase &&
            state.candidates[state.attempt] == candidate
    }

    private fun advance(
        state: Active,
        terminalFailure: PlaybackRecoveryFailure,
    ): PlaybackRecoveryAction {
        if (isExpired(state)) {
            return fail(state, PlaybackRecoveryFailure.DeadlineExceeded)
        }
        if (state.attempt + 1 >= state.candidates.size) {
            return fail(state, terminalFailure)
        }
        state.attempt += 1
        state.phase = Phase.Resolving
        return state.resolveAction()
    }

    private fun isExpired(state: Active): Boolean =
        elapsedRealtimeMillis() >= state.deadlineAtMillis

    private fun fail(
        state: Active,
        failure: PlaybackRecoveryFailure,
    ): PlaybackRecoveryAction {
        active = null
        return PlaybackRecoveryAction.Failed(state.generation, failure, state.attempt)
    }

    private data class Active(
        val generation: Long,
        val deadlineAtMillis: Long,
        val candidates: List<PlaybackCandidateIdentity>,
        var attempt: Int,
        var phase: Phase,
    ) {
        fun resolveAction(): PlaybackRecoveryAction.ResolveCandidate =
            PlaybackRecoveryAction.ResolveCandidate(
                generation = generation,
                candidate = candidates[attempt],
                attempt = attempt,
            )
    }

    private enum class Phase {
        Resolving,
        Installed,
    }
}

private fun ResolvedPlaybackRequest.matches(candidate: PlaybackCandidateIdentity): Boolean =
    channelId == candidate.channelId && variantId == candidate.variantId

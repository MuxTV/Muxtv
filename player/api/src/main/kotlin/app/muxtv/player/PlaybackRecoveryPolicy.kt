package app.muxtv.player

import app.muxtv.common.CanonicalChannelId

data class PlaybackRecoveryCandidate(
    val channelId: CanonicalChannelId,
    val variantId: StreamVariantId,
)

data class PlaybackRecoveryBudget(
    val maxAttempts: Int,
    val maxRecoveryDurationMillis: Long,
) {
    init {
        require(maxAttempts > 0)
        require(maxRecoveryDurationMillis > 0)
    }
}

enum class PlaybackRecoveryDisposition {
    TRY_NEXT_CANDIDATE,
    STOP_RECOVERY,
}

@JvmInline
value class PlaybackRecoveryGeneration(
    val value: Long,
) {
    init {
        require(value > 0)
    }
}

class PlaybackRecoveryPlan private constructor(
    val canonicalChannelId: CanonicalChannelId,
    val orderedCandidates: List<PlaybackRecoveryCandidate>,
    val budget: PlaybackRecoveryBudget,
) {
    fun candidateAt(
        attemptIndex: Int,
        elapsedRecoveryMillis: Long,
    ): PlaybackRecoveryCandidate? {
        require(attemptIndex >= 0)
        require(elapsedRecoveryMillis >= 0)
        if (elapsedRecoveryMillis >= budget.maxRecoveryDurationMillis) {
            return null
        }
        return orderedCandidates.getOrNull(attemptIndex)
    }

    fun candidateAfterFailure(
        failedAttemptIndex: Int,
        elapsedRecoveryMillis: Long,
        disposition: PlaybackRecoveryDisposition,
    ): PlaybackRecoveryCandidate? {
        require(failedAttemptIndex >= 0)
        require(elapsedRecoveryMillis >= 0)
        if (disposition == PlaybackRecoveryDisposition.STOP_RECOVERY) {
            return null
        }
        if (failedAttemptIndex >= orderedCandidates.lastIndex) {
            return null
        }
        return candidateAt(
            attemptIndex = failedAttemptIndex + 1,
            elapsedRecoveryMillis = elapsedRecoveryMillis,
        )
    }

    companion object {
        fun create(
            canonicalChannelId: CanonicalChannelId,
            candidates: List<PlaybackRecoveryCandidate>,
            preferredVariantId: StreamVariantId?,
            budget: PlaybackRecoveryBudget,
        ): PlaybackRecoveryPlan {
            require(candidates.all { candidate -> candidate.channelId == canonicalChannelId })

            val snapshot = candidates.distinctBy { candidate -> candidate.variantId }
            val preferredIndex = snapshot.indexOfFirst { candidate ->
                candidate.variantId == preferredVariantId
            }
            val preferredFirstCandidates = if (preferredIndex > 0) {
                buildList(snapshot.size) {
                    add(snapshot[preferredIndex])
                    snapshot.forEachIndexed { index, candidate ->
                        if (index != preferredIndex) {
                            add(candidate)
                        }
                    }
                }
            } else {
                snapshot
            }
            val orderedCandidates = preferredFirstCandidates.take(budget.maxAttempts)

            return PlaybackRecoveryPlan(
                canonicalChannelId = canonicalChannelId,
                orderedCandidates = orderedCandidates,
                budget = budget,
            )
        }
    }
}

class PlaybackRecoverySession private constructor(
    val generation: PlaybackRecoveryGeneration,
    val plan: PlaybackRecoveryPlan,
    val isCancelled: Boolean,
) {
    fun candidateAfterFailure(
        callbackGeneration: PlaybackRecoveryGeneration,
        failedAttemptIndex: Int,
        elapsedRecoveryMillis: Long,
        disposition: PlaybackRecoveryDisposition,
    ): PlaybackRecoveryCandidate? {
        if (isCancelled || callbackGeneration != generation) {
            return null
        }
        return plan.candidateAfterFailure(
            failedAttemptIndex = failedAttemptIndex,
            elapsedRecoveryMillis = elapsedRecoveryMillis,
            disposition = disposition,
        )
    }

    fun cancel(callbackGeneration: PlaybackRecoveryGeneration): PlaybackRecoverySession {
        if (callbackGeneration != generation || isCancelled) {
            return this
        }
        return PlaybackRecoverySession(
            generation = generation,
            plan = plan,
            isCancelled = true,
        )
    }

    fun supersede(
        newGeneration: PlaybackRecoveryGeneration,
        newPlan: PlaybackRecoveryPlan,
    ): PlaybackRecoverySession {
        require(newGeneration.value > generation.value)
        return PlaybackRecoverySession(
            generation = newGeneration,
            plan = newPlan,
            isCancelled = false,
        )
    }

    companion object {
        fun create(
            generation: PlaybackRecoveryGeneration,
            plan: PlaybackRecoveryPlan,
        ): PlaybackRecoverySession = PlaybackRecoverySession(
            generation = generation,
            plan = plan,
            isCancelled = false,
        )
    }
}

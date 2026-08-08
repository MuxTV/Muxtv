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

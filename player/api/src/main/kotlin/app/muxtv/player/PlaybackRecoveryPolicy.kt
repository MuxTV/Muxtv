package app.muxtv.player

import app.muxtv.common.CanonicalChannelId

data class PlaybackRecoveryCandidate(
    val channelId: CanonicalChannelId,
    val variantId: StreamVariantId,
)

data class PlaybackRecoveryBudget(
    val maxAttempts: Int,
    val maxRecoveryDurationMillis: Long,
)

class PlaybackRecoveryPlan private constructor(
    val canonicalChannelId: CanonicalChannelId,
    val orderedCandidates: List<PlaybackRecoveryCandidate>,
    val budget: PlaybackRecoveryBudget,
) {
    companion object {
        fun create(
            canonicalChannelId: CanonicalChannelId,
            candidates: List<PlaybackRecoveryCandidate>,
            preferredVariantId: StreamVariantId?,
            budget: PlaybackRecoveryBudget,
        ): PlaybackRecoveryPlan {
            val snapshot = candidates.distinctBy { candidate -> candidate.variantId }
            val preferredIndex = snapshot.indexOfFirst { candidate ->
                candidate.variantId == preferredVariantId
            }
            val orderedCandidates = if (preferredIndex > 0) {
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

            return PlaybackRecoveryPlan(
                canonicalChannelId = canonicalChannelId,
                orderedCandidates = orderedCandidates,
                budget = budget,
            )
        }
    }
}

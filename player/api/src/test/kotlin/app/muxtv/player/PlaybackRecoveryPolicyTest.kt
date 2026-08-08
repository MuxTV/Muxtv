package app.muxtv.player

import app.muxtv.common.CanonicalChannelId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun `preferred same-channel candidate is attempted first`() {
        val channelId = CanonicalChannelId("channel-a")
        val preferredVariantId = StreamVariantId("variant-b")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(
                    channelId = channelId,
                    variantId = StreamVariantId("variant-a"),
                ),
                PlaybackRecoveryCandidate(
                    channelId = channelId,
                    variantId = preferredVariantId,
                ),
                PlaybackRecoveryCandidate(
                    channelId = channelId,
                    variantId = StreamVariantId("variant-c"),
                ),
            ),
            preferredVariantId = preferredVariantId,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 3,
                maxRecoveryDurationMillis = 10_000L,
            ),
        )

        assertThat(plan.orderedCandidates.first().variantId).isEqualTo(preferredVariantId)
    }
}

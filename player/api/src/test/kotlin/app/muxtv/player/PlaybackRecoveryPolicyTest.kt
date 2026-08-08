package app.muxtv.player

import app.muxtv.common.CanonicalChannelId
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun `preferred same-channel candidate is attempted first and remainder stays stable`() {
        val channelId = CanonicalChannelId("channel-a")
        val variantA = StreamVariantId("variant-a")
        val preferredVariantId = StreamVariantId("variant-b")
        val variantC = StreamVariantId("variant-c")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId = channelId, variantId = variantA),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = preferredVariantId),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = variantC),
            ),
            preferredVariantId = preferredVariantId,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 3,
                maxRecoveryDurationMillis = 10_000L,
            ),
        )

        assertThat(plan.orderedCandidates.map { it.variantId })
            .containsExactly(preferredVariantId, variantA, variantC)
            .inOrder()
    }

    @Test
    fun `duplicate same-channel variant identity is attempted once using first occurrence`() {
        val channelId = CanonicalChannelId("channel-a")
        val variantA = StreamVariantId("variant-a")
        val preferredVariantId = StreamVariantId("variant-b")
        val variantC = StreamVariantId("variant-c")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId = channelId, variantId = variantA),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = preferredVariantId),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = preferredVariantId),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = variantC),
            ),
            preferredVariantId = preferredVariantId,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 4,
                maxRecoveryDurationMillis = 10_000L,
            ),
        )

        assertThat(plan.orderedCandidates.map { it.variantId })
            .containsExactly(preferredVariantId, variantA, variantC)
            .inOrder()
    }

    @Test
    fun `candidate from another canonical channel is rejected`() {
        val channelId = CanonicalChannelId("channel-a")
        val foreignChannelId = CanonicalChannelId("channel-b")

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryPlan.create(
                canonicalChannelId = channelId,
                candidates = listOf(
                    PlaybackRecoveryCandidate(
                        channelId = channelId,
                        variantId = StreamVariantId("variant-a"),
                    ),
                    PlaybackRecoveryCandidate(
                        channelId = foreignChannelId,
                        variantId = StreamVariantId("variant-b"),
                    ),
                ),
                preferredVariantId = null,
                budget = PlaybackRecoveryBudget(
                    maxAttempts = 2,
                    maxRecoveryDurationMillis = 10_000L,
                ),
            )
        }
    }

    @Test
    fun `attempt budget must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryBudget(
                maxAttempts = 0,
                maxRecoveryDurationMillis = 10_000L,
            )
        }
    }

    @Test
    fun `ordered candidates are capped by maximum attempts after preferred ordering`() {
        val channelId = CanonicalChannelId("channel-a")
        val variantA = StreamVariantId("variant-a")
        val preferredVariantId = StreamVariantId("variant-b")
        val variantC = StreamVariantId("variant-c")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId = channelId, variantId = variantA),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = preferredVariantId),
                PlaybackRecoveryCandidate(channelId = channelId, variantId = variantC),
            ),
            preferredVariantId = preferredVariantId,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 2,
                maxRecoveryDurationMillis = 10_000L,
            ),
        )

        assertThat(plan.orderedCandidates.map { it.variantId })
            .containsExactly(preferredVariantId, variantA)
            .inOrder()
    }
}

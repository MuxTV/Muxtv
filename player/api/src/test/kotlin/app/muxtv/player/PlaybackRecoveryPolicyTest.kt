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

    @Test
    fun `total recovery duration budget must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryBudget(
                maxAttempts = 1,
                maxRecoveryDurationMillis = 0L,
            )
        }
    }

    @Test
    fun `candidate is available immediately before deadline and unavailable at deadline`() {
        val channelId = CanonicalChannelId("channel-a")
        val preferredVariantId = StreamVariantId("variant-b")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-a")),
                PlaybackRecoveryCandidate(channelId, preferredVariantId),
            ),
            preferredVariantId = preferredVariantId,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 2,
                maxRecoveryDurationMillis = 1_000L,
            ),
        )

        assertThat(plan.candidateAt(attemptIndex = 0, elapsedRecoveryMillis = 999L)?.variantId)
            .isEqualTo(preferredVariantId)
        assertThat(plan.candidateAt(attemptIndex = 0, elapsedRecoveryMillis = 1_000L)).isNull()
    }

    @Test
    fun `candidate lookup returns null after capped attempt list`() {
        val channelId = CanonicalChannelId("channel-a")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-a")),
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-b")),
            ),
            preferredVariantId = null,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 1,
                maxRecoveryDurationMillis = 1_000L,
            ),
        )

        assertThat(plan.candidateAt(attemptIndex = 1, elapsedRecoveryMillis = 0L)).isNull()
    }

    @Test
    fun `candidate lookup rejects negative attempt index and elapsed duration`() {
        val channelId = CanonicalChannelId("channel-a")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-a")),
            ),
            preferredVariantId = null,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 1,
                maxRecoveryDurationMillis = 1_000L,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            plan.candidateAt(attemptIndex = -1, elapsedRecoveryMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.candidateAt(attemptIndex = 0, elapsedRecoveryMillis = -1L)
        }
    }
}

package app.muxtv.player

import app.muxtv.common.CanonicalChannelId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackRecoveryPreferenceTest {
    @Test
    fun `temporary fallback selection does not rewrite preferred variant identity`() {
        val channelId = CanonicalChannelId("channel-a")
        val preferredVariantId = StreamVariantId("variant-b")
        val fallbackVariantId = StreamVariantId("variant-a")
        val plan = PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId, fallbackVariantId),
                PlaybackRecoveryCandidate(channelId, preferredVariantId),
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-c")),
            ),
            preferredVariantId = preferredVariantId,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 3,
                maxRecoveryDurationMillis = 1_000L,
            ),
        )

        val fallbackCandidate = plan.candidateAt(
            attemptIndex = 1,
            elapsedRecoveryMillis = 100L,
        )

        assertThat(fallbackCandidate?.variantId).isEqualTo(fallbackVariantId)
        assertThat(plan.preferredVariantId).isEqualTo(preferredVariantId)
    }

    @Test
    fun `missing preferred variant remains absent without synthesizing preference`() {
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

        assertThat(plan.preferredVariantId).isNull()
    }
}

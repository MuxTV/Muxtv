package app.muxtv.player

import app.muxtv.common.CanonicalChannelId
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackRecoveryDispositionTest {
    @Test
    fun `try-next disposition advances to next candidate before deadline`() {
        val plan = createPlan()

        assertThat(
            plan.candidateAfterFailure(
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 999L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            )?.variantId,
        ).isEqualTo(StreamVariantId("variant-b"))
    }

    @Test
    fun `stop disposition never advances to another candidate`() {
        val plan = createPlan()

        assertThat(
            plan.candidateAfterFailure(
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.STOP_RECOVERY,
            ),
        ).isNull()
    }

    @Test
    fun `try-next disposition still obeys total recovery deadline`() {
        val plan = createPlan()

        assertThat(
            plan.candidateAfterFailure(
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 1_000L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            ),
        ).isNull()
    }

    @Test
    fun `failure advancement rejects negative attempt index`() {
        val plan = createPlan()

        assertThrows(IllegalArgumentException::class.java) {
            plan.candidateAfterFailure(
                failedAttemptIndex = -1,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            )
        }
    }

    private fun createPlan(): PlaybackRecoveryPlan {
        val channelId = CanonicalChannelId("channel-a")
        return PlaybackRecoveryPlan.create(
            canonicalChannelId = channelId,
            candidates = listOf(
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-a")),
                PlaybackRecoveryCandidate(channelId, StreamVariantId("variant-b")),
            ),
            preferredVariantId = null,
            budget = PlaybackRecoveryBudget(
                maxAttempts = 2,
                maxRecoveryDurationMillis = 1_000L,
            ),
        )
    }
}

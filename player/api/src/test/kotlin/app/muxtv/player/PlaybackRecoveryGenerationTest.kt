package app.muxtv.player

import app.muxtv.common.CanonicalChannelId
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackRecoveryGenerationTest {
    @Test
    fun `generation must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRecoveryGeneration(0L)
        }
    }

    @Test
    fun `current generation can advance after retryable failure`() {
        val generation = PlaybackRecoveryGeneration(1L)
        val session = PlaybackRecoverySession.create(generation, createPlan())

        assertThat(
            session.candidateAfterFailure(
                callbackGeneration = generation,
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            )?.variantId,
        ).isEqualTo(StreamVariantId("variant-b"))
    }

    @Test
    fun `cancelled current generation becomes inert`() {
        val generation = PlaybackRecoveryGeneration(1L)
        val session = PlaybackRecoverySession.create(generation, createPlan())
            .cancel(generation)

        assertThat(
            session.candidateAfterFailure(
                callbackGeneration = generation,
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            ),
        ).isNull()
    }

    @Test
    fun `superseded generation ignores stale callback while new generation stays active`() {
        val oldGeneration = PlaybackRecoveryGeneration(1L)
        val newGeneration = PlaybackRecoveryGeneration(2L)
        val session = PlaybackRecoverySession.create(oldGeneration, createPlan())
            .supersede(newGeneration = newGeneration, newPlan = createPlan())

        assertThat(
            session.candidateAfterFailure(
                callbackGeneration = oldGeneration,
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            ),
        ).isNull()
        assertThat(
            session.candidateAfterFailure(
                callbackGeneration = newGeneration,
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            )?.variantId,
        ).isEqualTo(StreamVariantId("variant-b"))
    }

    @Test
    fun `stale cancellation cannot cancel superseding generation`() {
        val oldGeneration = PlaybackRecoveryGeneration(1L)
        val newGeneration = PlaybackRecoveryGeneration(2L)
        val session = PlaybackRecoverySession.create(oldGeneration, createPlan())
            .supersede(newGeneration = newGeneration, newPlan = createPlan())
            .cancel(oldGeneration)

        assertThat(
            session.candidateAfterFailure(
                callbackGeneration = newGeneration,
                failedAttemptIndex = 0,
                elapsedRecoveryMillis = 0L,
                disposition = PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE,
            )?.variantId,
        ).isEqualTo(StreamVariantId("variant-b"))
    }

    @Test
    fun `superseding generation must move forward`() {
        val generation = PlaybackRecoveryGeneration(2L)
        val session = PlaybackRecoverySession.create(generation, createPlan())

        assertThrows(IllegalArgumentException::class.java) {
            session.supersede(
                newGeneration = PlaybackRecoveryGeneration(1L),
                newPlan = createPlan(),
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

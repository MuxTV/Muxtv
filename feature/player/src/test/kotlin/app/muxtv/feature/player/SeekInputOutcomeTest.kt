package app.muxtv.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekInputOutcomeTest {
    @Test
    fun `submitted handles the key without claiming authoritative acceptance`() {
        assertThat(SeekInputOutcome.SUBMITTED.diagnosticTag).isEqualTo("submitted")
        assertThat(SeekInputOutcome.SUBMITTED.handlesDispatch).isTrue()
        assertThat(SeekInputOutcome.SUBMITTED.publishesSemanticOutcome).isFalse()
    }

    @Test
    fun `service accepted preserves the existing accepted evidence tag`() {
        assertThat(SeekInputOutcome.SERVICE_ACCEPTED.diagnosticTag).isEqualTo("accepted")
        assertThat(SeekInputOutcome.SERVICE_ACCEPTED.handlesDispatch).isFalse()
        assertThat(SeekInputOutcome.SERVICE_ACCEPTED.publishesSemanticOutcome).isTrue()
    }

    @Test
    fun `local rejection does not consume seek dispatch but remains diagnosable`() {
        val rejections = listOf(
            SeekInputOutcome.COMMAND_UNAVAILABLE,
            SeekInputOutcome.UNKNOWN_DURATION,
            SeekInputOutcome.LIVE_CONTENT,
            SeekInputOutcome.INVALID_POSITION,
            SeekInputOutcome.CONTROLLER_REJECTED,
        )

        rejections.forEach { outcome ->
            assertThat(outcome.handlesDispatch).isFalse()
            assertThat(outcome.publishesSemanticOutcome).isTrue()
        }
    }
}

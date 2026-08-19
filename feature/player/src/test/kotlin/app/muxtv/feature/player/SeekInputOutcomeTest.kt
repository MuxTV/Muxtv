package app.muxtv.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekInputOutcomeTest {
    @Test
    fun `submitted handles the key while service accepted is a later authority result`() {
        assertThat(SeekInputOutcome.SUBMITTED.diagnosticTag).isEqualTo("submitted")
        assertThat(SeekInputOutcome.SUBMITTED.handlesDispatch).isTrue()

        assertThat(SeekInputOutcome.SERVICE_ACCEPTED.diagnosticTag)
            .isEqualTo("service-accepted")
        assertThat(SeekInputOutcome.SERVICE_ACCEPTED.handlesDispatch).isFalse()
    }

    @Test
    fun `local rejection does not consume seek dispatch`() {
        assertThat(SeekInputOutcome.COMMAND_UNAVAILABLE.handlesDispatch).isFalse()
        assertThat(SeekInputOutcome.UNKNOWN_DURATION.handlesDispatch).isFalse()
        assertThat(SeekInputOutcome.LIVE_CONTENT.handlesDispatch).isFalse()
        assertThat(SeekInputOutcome.INVALID_POSITION.handlesDispatch).isFalse()
        assertThat(SeekInputOutcome.CONTROLLER_REJECTED.handlesDispatch).isFalse()
    }
}

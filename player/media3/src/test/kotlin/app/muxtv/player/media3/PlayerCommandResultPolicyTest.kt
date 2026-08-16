package app.muxtv.player.media3

import androidx.media3.session.SessionResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerCommandResultPolicyTest {
    @Test
    fun `ordinary player command returns session success`() {
        assertThat(playerCommandSessionResult(coalescedByService = false))
            .isEqualTo(SessionResult.RESULT_SUCCESS)
    }

    @Test
    fun `service consumed seek returns informational skipped result`() {
        assertThat(playerCommandSessionResult(coalescedByService = true))
            .isEqualTo(SessionResult.RESULT_INFO_SKIPPED)
    }
}

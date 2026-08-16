package app.muxtv.feature.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerRemoteInputHostTest {
    @Test
    fun `dispatch routes command only to active registration`() {
        val host = PlayerRemoteInputHost()
        val received = mutableListOf<PlayerRemoteCommand>()
        val registration = host.attach { command ->
            received += command
            true
        }

        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_FORWARD)).isTrue()
        assertThat(received).containsExactly(PlayerRemoteCommand.SEEK_FORWARD)

        registration.close()

        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_BACKWARD)).isFalse()
        assertThat(received).containsExactly(PlayerRemoteCommand.SEEK_FORWARD)
    }

    @Test
    fun `disposing stale registration cannot clear newer handler`() {
        val host = PlayerRemoteInputHost()
        val first = host.attach { false }
        val received = mutableListOf<PlayerRemoteCommand>()
        val second = host.attach { command ->
            received += command
            true
        }

        first.close()

        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_BACKWARD)).isTrue()
        assertThat(received).containsExactly(PlayerRemoteCommand.SEEK_BACKWARD)

        second.close()
        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_FORWARD)).isFalse()
    }

    @Test
    fun `semantic outcome belongs only to current dispatch`() {
        val host = PlayerRemoteInputHost()
        var recordOutcome = true
        val registration = host.attach {
            if (recordOutcome) host.recordSemanticOutcome("accepted")
            recordOutcome
        }

        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_FORWARD)).isTrue()
        assertThat(host.diagnosticsSnapshot().lastSemanticOutcome).isEqualTo("accepted")

        recordOutcome = false
        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_FORWARD)).isFalse()
        assertThat(host.diagnosticsSnapshot().lastSemanticOutcome).isNull()

        registration.close()
        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_BACKWARD)).isFalse()
        assertThat(host.diagnosticsSnapshot().lastSemanticOutcome).isNull()
    }

    @Test
    fun `diagnostics distinguish registration dispatch and close boundaries`() {
        val host = PlayerRemoteInputHost()

        assertThat(host.diagnosticsSnapshot()).isEqualTo(
            PlayerRemoteInputDiagnostics(
                attachGeneration = 0L,
                hasActiveHandler = false,
                dispatchCount = 0L,
                lastDispatchHadActiveHandler = null,
                lastDispatchHandled = null,
            ),
        )

        val registration = host.attach { true }
        assertThat(host.diagnosticsSnapshot()).isEqualTo(
            PlayerRemoteInputDiagnostics(
                attachGeneration = 1L,
                hasActiveHandler = true,
                dispatchCount = 0L,
                lastDispatchHadActiveHandler = null,
                lastDispatchHandled = null,
            ),
        )

        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_FORWARD)).isTrue()
        assertThat(host.diagnosticsSnapshot()).isEqualTo(
            PlayerRemoteInputDiagnostics(
                attachGeneration = 1L,
                hasActiveHandler = true,
                dispatchCount = 1L,
                lastDispatchHadActiveHandler = true,
                lastDispatchHandled = true,
            ),
        )

        registration.close()
        assertThat(host.diagnosticsSnapshot().hasActiveHandler).isFalse()

        assertThat(host.dispatch(PlayerRemoteCommand.SEEK_BACKWARD)).isFalse()
        assertThat(host.diagnosticsSnapshot()).isEqualTo(
            PlayerRemoteInputDiagnostics(
                attachGeneration = 1L,
                hasActiveHandler = false,
                dispatchCount = 2L,
                lastDispatchHadActiveHandler = false,
                lastDispatchHandled = false,
            ),
        )
    }
}

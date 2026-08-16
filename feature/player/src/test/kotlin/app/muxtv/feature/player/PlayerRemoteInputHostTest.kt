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
}

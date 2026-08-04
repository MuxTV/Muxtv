package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSetupIdentityTest {
    @Test
    fun `install callback receives the exact accepted setup identity`() {
        val installed = mutableListOf<Pair<PlaybackSetupId, String>>()
        val coordinator = PlaybackSetupCoordinator<String>(
            install = { setupId, value -> installed += setupId to value },
            clearInstalled = {},
        )
        val setupId = requireNotNull(
            PlaybackSetupId.parse("00000000-0000-0000-0000-000000000201"),
        )

        val result = coordinator.install(setupId, "channel-a")

        assertThat(result).isEqualTo(PlaybackSetupInstallResult.Installed)
        assertThat(installed).containsExactly(setupId to "channel-a")
    }

    @Test
    fun `pre-cancelled setup never reaches identity-aware install callback`() {
        val installed = mutableListOf<Pair<PlaybackSetupId, String>>()
        val coordinator = PlaybackSetupCoordinator<String>(
            install = { setupId, value -> installed += setupId to value },
            clearInstalled = {},
        )
        val setupId = requireNotNull(
            PlaybackSetupId.parse("00000000-0000-0000-0000-000000000202"),
        )

        coordinator.cancel(setupId)
        val result = coordinator.install(setupId, "channel-a")

        assertThat(result).isEqualTo(PlaybackSetupInstallResult.Cancelled)
        assertThat(installed).isEmpty()
    }
}

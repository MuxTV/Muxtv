package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class PlaybackSetupCoordinatorTest {
    @Test
    fun `cancel before install rejects the matching setup`() {
        val installed = mutableListOf<RequestRef>()
        val coordinator = coordinator(install = installed::add)
        val setupId = setupId("00000000-0000-0000-0000-000000000001")

        val cancelResult = coordinator.cancel(setupId)
        val installResult = coordinator.install(setupId, RequestRef("A"))

        assertThat(cancelResult).isEqualTo(PlaybackSetupCancelResult.PendingCancelled)
        assertThat(installResult).isEqualTo(PlaybackSetupInstallResult.Cancelled)
        assertThat(installed).isEmpty()
    }

    @Test
    fun `cancel of active setup clears exactly once`() {
        val clears = AtomicInteger()
        val coordinator = coordinator(clearInstalled = { clears.incrementAndGet() })
        val setupId = setupId("00000000-0000-0000-0000-000000000002")

        assertThat(coordinator.install(setupId, RequestRef("A")))
            .isEqualTo(PlaybackSetupInstallResult.Installed)
        val firstCancel = coordinator.cancel(setupId)
        val secondCancel = coordinator.cancel(setupId)

        assertThat(firstCancel).isEqualTo(PlaybackSetupCancelResult.ActiveCleared)
        assertThat(secondCancel).isEqualTo(PlaybackSetupCancelResult.AlreadyCancelled)
        assertThat(clears.get()).isEqualTo(1)
    }

    @Test
    fun `stale cancel never clears a newer setup`() {
        val installed = mutableListOf<RequestRef>()
        val clears = AtomicInteger()
        val coordinator = coordinator(
            install = installed::add,
            clearInstalled = { clears.incrementAndGet() },
        )
        val firstId = setupId("00000000-0000-0000-0000-000000000003")
        val secondId = setupId("00000000-0000-0000-0000-000000000004")
        val first = RequestRef("A")
        val second = RequestRef("B")

        coordinator.install(firstId, first)
        coordinator.install(secondId, second)
        val cancelResult = coordinator.cancel(firstId)

        assertThat(cancelResult).isEqualTo(PlaybackSetupCancelResult.PendingCancelled)
        assertThat(clears.get()).isEqualTo(0)
        assertThat(installed).containsExactly(first, second).inOrder()
    }

    @Test
    fun `repeated pending cancel is idempotent`() {
        val coordinator = coordinator()
        val setupId = setupId("00000000-0000-0000-0000-000000000005")

        val first = coordinator.cancel(setupId)
        val second = coordinator.cancel(setupId)

        assertThat(first).isEqualTo(PlaybackSetupCancelResult.PendingCancelled)
        assertThat(second).isEqualTo(PlaybackSetupCancelResult.AlreadyCancelled)
    }

    @Test
    fun `cancel memory evicts the oldest id at the configured bound`() {
        val installed = mutableListOf<RequestRef>()
        val coordinator = coordinator(
            cancelledCapacity = 2,
            install = installed::add,
        )
        val firstId = setupId("00000000-0000-0000-0000-000000000006")
        val secondId = setupId("00000000-0000-0000-0000-000000000007")
        val thirdId = setupId("00000000-0000-0000-0000-000000000008")

        coordinator.cancel(firstId)
        coordinator.cancel(secondId)
        coordinator.cancel(thirdId)

        val firstInstall = coordinator.install(firstId, RequestRef("A"))
        val secondInstall = coordinator.install(secondId, RequestRef("B"))
        val thirdInstall = coordinator.install(thirdId, RequestRef("C"))

        assertThat(firstInstall).isEqualTo(PlaybackSetupInstallResult.Installed)
        assertThat(secondInstall).isEqualTo(PlaybackSetupInstallResult.Cancelled)
        assertThat(thirdInstall).isEqualTo(PlaybackSetupInstallResult.Cancelled)
        assertThat(installed.map(RequestRef::label)).containsExactly("A")
    }

    @Test
    fun `setup id rejects malformed values and redacts diagnostics`() {
        val raw = "00000000-0000-0000-0000-000000000009"
        val setupId = PlaybackSetupId.parse(raw)

        assertThat(setupId).isNotNull()
        assertThat(setupId!!.encoded()).isEqualTo(raw)
        assertThat(setupId.toString()).isEqualTo("PlaybackSetupId(<redacted>)")
        assertThat(setupId.toString()).doesNotContain(raw)
        assertThat(PlaybackSetupId.parse(null)).isNull()
        assertThat(PlaybackSetupId.parse("")).isNull()
        assertThat(PlaybackSetupId.parse("contains whitespace")).isNull()
        assertThat(PlaybackSetupId.parse("x".repeat(65))).isNull()
    }

    @Test
    fun `cancel active A then install B clears once and installs B`() {
        val installed = mutableListOf<RequestRef>()
        val clears = AtomicInteger()
        val coordinator = coordinator(
            install = installed::add,
            clearInstalled = { clears.incrementAndGet() },
        )
        val firstId = setupId("00000000-0000-0000-0000-000000000010")
        val secondId = setupId("00000000-0000-0000-0000-000000000011")
        val first = RequestRef("A")
        val second = RequestRef("B")

        coordinator.install(firstId, first)
        coordinator.cancel(firstId)
        val secondInstall = coordinator.install(secondId, second)

        assertThat(secondInstall).isEqualTo(PlaybackSetupInstallResult.Installed)
        assertThat(clears.get()).isEqualTo(1)
        assertThat(installed).containsExactly(first, second).inOrder()
    }

    private fun coordinator(
        cancelledCapacity: Int = 64,
        install: (RequestRef) -> Unit = {},
        clearInstalled: () -> Unit = {},
    ): PlaybackSetupCoordinator<RequestRef> = PlaybackSetupCoordinator(
        cancelledCapacity = cancelledCapacity,
        install = { _, value -> install(value) },
        clearInstalled = clearInstalled,
    )

    private fun setupId(raw: String): PlaybackSetupId =
        requireNotNull(PlaybackSetupId.parse(raw))

    private class RequestRef(
        val label: String,
    )
}

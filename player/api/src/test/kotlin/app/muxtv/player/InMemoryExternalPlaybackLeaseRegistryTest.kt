package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InMemoryExternalPlaybackLeaseRegistryTest {
    @Test
    fun `registered lease is claimed exactly once`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry()
        val descriptor = descriptor("http://192.168.1.10:8090/stream/file.mkv")
        val leaseId = registry.register(descriptor, "session-1", nowEpochMillis = 0L)

        val claimed = registry.claim(leaseId, nowEpochMillis = 0L)
        assertThat(claimed).isEqualTo(
            ExternalPlaybackClaimResult.Claimed(
                descriptor = descriptor,
                sessionId = "session-1",
            ),
        )

        assertThat(registry.claim(leaseId, nowEpochMillis = 0L))
            .isEqualTo(ExternalPlaybackClaimResult.Unknown)
    }

    @Test
    fun `expired lease cannot be claimed`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry(leaseTtlMillis = 100L)
        val leaseId = registry.register(
            descriptor("http://192.168.1.10:8090/stream/file.mkv"),
            "session-1",
            nowEpochMillis = 0L,
        )

        assertThat(registry.claim(leaseId, nowEpochMillis = 100L))
            .isEqualTo(ExternalPlaybackClaimResult.Expired)
        assertThat(registry.claim(leaseId, nowEpochMillis = 100L))
            .isEqualTo(ExternalPlaybackClaimResult.Unknown)
    }

    @Test
    fun `capacity eviction drops oldest entry`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry(capacity = 2)
        val first = registry.register(descriptor("http://a.local/first.mkv"), "s-1", 0L)
        val second = registry.register(descriptor("http://a.local/second.mkv"), "s-2", 0L)
        registry.register(descriptor("http://a.local/third.mkv"), "s-3", 0L)

        assertThat(registry.claim(first, 0L)).isEqualTo(ExternalPlaybackClaimResult.Unknown)
        assertThat(registry.claim(second, 0L)).isInstanceOf(
            ExternalPlaybackClaimResult.Claimed::class.java,
        )
    }

    @Test
    fun `re-registering the same session replaces the previous lease`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry()
        val first = registry.register(descriptor("http://a.local/first.mkv"), "s-1", 0L)
        val second = registry.register(descriptor("http://a.local/second.mkv"), "s-1", 0L)

        assertThat(registry.claim(first, 0L)).isEqualTo(ExternalPlaybackClaimResult.Unknown)
        assertThat(registry.claim(second, 0L)).isInstanceOf(
            ExternalPlaybackClaimResult.Claimed::class.java,
        )
    }

    @Test
    fun `removeSession drops all leases of a session`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry()
        val leaseId = registry.register(descriptor("http://a.local/file.mkv"), "s-1", 0L)
        registry.removeSession("s-1")

        assertThat(registry.claim(leaseId, 0L)).isEqualTo(ExternalPlaybackClaimResult.Unknown)
    }

    @Test
    fun `expired entries do not consume capacity`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry(capacity = 2, leaseTtlMillis = 50L)
        registry.register(descriptor("http://a.local/first.mkv"), "s-1", 0L)
        val second = registry.register(descriptor("http://a.local/second.mkv"), "s-2", 0L)
        val third = registry.register(descriptor("http://a.local/third.mkv"), "s-3", 100L)

        assertThat(registry.size()).isEqualTo(1)
        assertThat(registry.claim(second, 100L)).isEqualTo(ExternalPlaybackClaimResult.Unknown)
        assertThat(registry.claim(third, 100L)).isInstanceOf(
            ExternalPlaybackClaimResult.Claimed::class.java,
        )
    }

    @Test
    fun `blank session ids are rejected`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry()
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(descriptor("http://a.local/file.mkv"), "", 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(descriptor("http://a.local/file.mkv"), "has\nnewline", 0L)
        }
    }

    @Test
    fun `registry never reveals descriptor contents in ids or results`() {
        val registry = InMemoryExternalPlaybackLeaseRegistry()
        val secret = "http://192.168.1.10:8090/stream/x.mkv?link=torrent-hash&index=2&play"
        val leaseId = registry.register(descriptor(secret), "session-1", 0L)

        assertThat(leaseId.toString()).doesNotContain("torrent-hash")
        assertThat(leaseId.toString()).doesNotContain("192.168.1.10")
        assertThat(leaseId.encoded()).doesNotContain("torrent-hash")
    }

    private fun descriptor(locator: String): ExternalPlaybackDescriptor =
        ExternalPlaybackDescriptor(locator = locator)
}

package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalPlaybackOriginGrantsTest {
    @Test
    fun `approve adds exact origins and contains matches`() {
        val grants = ExternalPlaybackOriginGrants()
        val origin = ExternalPlaybackOrigin.parse("http://192.168.1.10:8090")
            ?: error("origin must parse")

        assertThat(grants.contains(origin)).isFalse()
        assertThat(grants.approve(origin))
            .isEqualTo(ExternalPlaybackOriginGrantResult.Applied)
        assertThat(grants.contains(origin)).isTrue()
        assertThat(grants.approve(origin))
            .isEqualTo(ExternalPlaybackOriginGrantResult.Unchanged)
    }

    @Test
    fun `port difference requires a separate grant`() {
        val grants = ExternalPlaybackOriginGrants()
        val first = ExternalPlaybackOrigin.parse("http://192.168.1.10:8090") ?: return
        val second = ExternalPlaybackOrigin.parse("http://192.168.1.10:8091") ?: return

        grants.approve(first)

        assertThat(grants.contains(second)).isFalse()
    }

    @Test
    fun `capacity is bounded`() {
        val grants = ExternalPlaybackOriginGrants(maxOrigins = 2)
        grants.approve(ExternalPlaybackOrigin.parse("http://a.local") ?: return)
        grants.approve(ExternalPlaybackOrigin.parse("http://b.local") ?: return)

        assertThat(grants.approve(ExternalPlaybackOrigin.parse("http://c.local") ?: return))
            .isEqualTo(ExternalPlaybackOriginGrantResult.CapacityExceeded)
    }

    @Test
    fun `revokeAll clears grants`() {
        val grants = ExternalPlaybackOriginGrants()
        val origin = ExternalPlaybackOrigin.parse("http://a.local") ?: return
        grants.approve(origin)

        grants.revokeAll()

        assertThat(grants.contains(origin)).isFalse()
    }

    @Test
    fun `restore accepts valid origins and rejects corruption`() {
        val grants = ExternalPlaybackOriginGrants()

        assertThat(grants.restore(listOf("http://a.local:8090", "https://b.example.org")))
            .isEqualTo(ExternalPlaybackOriginGrants.RestoreResult.Restored)
        assertThat(grants.contains(ExternalPlaybackOrigin.parse("http://a.local:8090") ?: return))
            .isTrue()

        assertThat(grants.restore(listOf("http://a.local:8090", "not-an-origin")))
            .isEqualTo(ExternalPlaybackOriginGrants.RestoreResult.Corrupted)
        assertThat(grants.snapshot()).isEmpty()
    }

    @Test
    fun `restore rejects oversized sets`() {
        val grants = ExternalPlaybackOriginGrants(maxOrigins = 2)

        assertThat(
            grants.restore(
                listOf("http://a.local", "http://b.local", "http://c.local"),
            ),
        ).isEqualTo(ExternalPlaybackOriginGrants.RestoreResult.Corrupted)
    }
}

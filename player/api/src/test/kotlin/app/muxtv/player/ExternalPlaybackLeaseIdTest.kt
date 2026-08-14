package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalPlaybackLeaseIdTest {
    @Test
    fun `created ids round trip through parse`() {
        val id = ExternalPlaybackLeaseId.create()

        assertThat(ExternalPlaybackLeaseId.parse(id.encoded())).isEqualTo(id)
    }

    @Test
    fun `invalid raw values are rejected`() {
        for (raw in listOf(null, "", "with spaces", "bad*value", "a".repeat(100))) {
            assertThat(ExternalPlaybackLeaseId.parse(raw)).isNull()
        }
    }

    @Test
    fun `ids are opaque and redacted`() {
        val id = ExternalPlaybackLeaseId.create()

        assertThat(id.toString()).isEqualTo("ExternalPlaybackLeaseId(<redacted>)")
    }
}

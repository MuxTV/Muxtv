package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactedUriTest {
    @Test
    fun `authority credentials and sensitive query values are hidden`() {
        val redacted = RedactedUri.from(
            "https://alice:secret@provider.example/playlist.m3u?token=abc123&group=News",
        ).toString()

        assertThat(redacted).doesNotContain("alice")
        assertThat(redacted).doesNotContain("secret")
        assertThat(redacted).doesNotContain("abc123")
        assertThat(redacted).contains("group=News")
        assertThat(redacted).contains("token=")
    }

    @Test
    fun `sensitive query names are matched case insensitively`() {
        val redacted = RedactedUri.from(
            "https://provider.example/list.m3u?ACCESS_TOKEN=first&ApiKey=second&safe=value",
        ).toString()

        assertThat(redacted).doesNotContain("first")
        assertThat(redacted).doesNotContain("second")
        assertThat(redacted).contains("safe=value")
    }

    @Test
    fun `safe URL remains readable`() {
        val redacted = RedactedUri.from(
            "https://provider.example:8443/list.m3u?group=News",
        ).toString()

        assertThat(redacted).isEqualTo(
            "https://provider.example:8443/list.m3u?group=News",
        )
    }

    @Test
    fun `malformed input never falls back to raw value`() {
        val raw = "https://[broken?token=top-secret"
        val redacted = RedactedUri.from(raw).toString()

        assertThat(redacted).isEqualTo("<invalid-url>")
        assertThat(redacted).doesNotContain("top-secret")
    }
}

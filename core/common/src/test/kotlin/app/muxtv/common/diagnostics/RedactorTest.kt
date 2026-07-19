package app.muxtv.common.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactorTest {
    @Test
    fun `redacts credentials and sensitive query values without losing safe context`() {
        val redacted = Redactor.redactText(
            "GET https://user:pass@example.test/live.m3u8?token=secret&quality=hd Authorization: Bearer abc Cookie: sid=123",
        )

        assertThat(redacted).doesNotContain("pass")
        assertThat(redacted).doesNotContain("secret")
        assertThat(redacted).doesNotContain("Bearer abc")
        assertThat(redacted).doesNotContain("sid=123")
        assertThat(redacted).contains("example.test/live.m3u8")
        assertThat(redacted).contains("quality=hd")
    }

    @Test
    fun `redacts common password keys case insensitively`() {
        val redacted = Redactor.redactText("username=dima&PASSWORD=qwerty&api_key=xyz")
        assertThat(redacted).doesNotContain("qwerty")
        assertThat(redacted).doesNotContain("xyz")
        assertThat(redacted).contains("username=dima")
    }
}

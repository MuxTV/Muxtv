package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExactHttpOriginTest {
    @Test
    fun `implicit and explicit port 80 produce the same origin`() {
        val implicit = ExactHttpOrigin.fromUrl("http://example.test/live")
        val explicit = ExactHttpOrigin.fromUrl("http://example.test:80/other")

        assertThat(implicit).isNotNull()
        assertThat(explicit).isEqualTo(implicit)
        assertThat(implicit!!.encoded()).isEqualTo("http://example.test:80")
    }

    @Test
    fun `different ports produce different origins`() {
        val defaultPort = ExactHttpOrigin.fromUrl("http://example.test/live")
        val alternatePort = ExactHttpOrigin.fromUrl("http://example.test:8080/live")

        assertThat(alternatePort).isNotEqualTo(defaultPort)
        assertThat(alternatePort!!.encoded()).isEqualTo("http://example.test:8080")
    }

    @Test
    fun `host and scheme are canonicalized`() {
        val origin = ExactHttpOrigin.fromUrl("HTTP://EXAMPLE.TEST/live")

        assertThat(origin!!.encoded()).isEqualTo("http://example.test:80")
        assertThat(origin.displayValue()).isEqualTo("http://example.test:80")
    }

    @Test
    fun `https is not an approvable HTTP origin`() {
        assertThat(ExactHttpOrigin.fromUrl("https://example.test/live")).isNull()
        assertThat(ExactHttpOrigin.parse("https://example.test:443")).isNull()
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertThat(ExactHttpOrigin.fromUrl("http://user:password@example.test/live")).isNull()
    }

    @Test
    fun `path query and fragment are removed from encoded origin`() {
        val origin = ExactHttpOrigin.fromUrl(
            "http://example.test:8080/path/segment?token=secret#fragment",
        )

        assertThat(origin!!.encoded()).isEqualTo("http://example.test:8080")
        assertThat(origin.encoded()).doesNotContain("path")
        assertThat(origin.encoded()).doesNotContain("token")
        assertThat(origin.encoded()).doesNotContain("fragment")
    }

    @Test
    fun `ipv6 origin uses canonical brackets and effective port`() {
        val origin = ExactHttpOrigin.fromUrl("http://[2001:db8::1]/live")

        assertThat(origin!!.encoded()).isEqualTo("http://[2001:db8::1]:80")
    }

    @Test
    fun `parse accepts only canonical exact origin encoding`() {
        val canonical = ExactHttpOrigin.parse("http://example.test:80")

        assertThat(canonical).isNotNull()
        assertThat(canonical!!.encoded()).isEqualTo("http://example.test:80")
        assertThat(ExactHttpOrigin.parse("http://EXAMPLE.TEST:80")).isNull()
        assertThat(ExactHttpOrigin.parse("http://example.test:80/")).isNull()
        assertThat(ExactHttpOrigin.parse("http://example.test")).isNull()
        assertThat(ExactHttpOrigin.parse("http://example.test:80/path")).isNull()
    }

    @Test
    fun `diagnostics redact the origin`() {
        val raw = "http://provider.example.test:8080"
        val origin = requireNotNull(ExactHttpOrigin.parse(raw))

        assertThat(origin.toString()).isEqualTo("ExactHttpOrigin(<redacted>)")
        assertThat(origin.toString()).doesNotContain(raw)
    }
}

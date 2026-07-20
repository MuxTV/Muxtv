package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Headers.Companion.headersOf
import org.junit.Test

class SensitiveHeaderPolicyTest {
    @Test
    fun `same-origin redirect preserves every header`() {
        val headers = headersOf(
            "Authorization", "Bearer secret",
            "Cookie", "session=secret",
            "Referer", "https://provider.example/setup",
            "User-Agent", "MuxTV/1",
        )

        val result = SensitiveHeaderPolicy.apply(
            headers = headers,
            disposition = RedirectHeaderDisposition.Preserve,
        )

        assertThat(result).isEqualTo(headers)
    }

    @Test
    fun `cross-origin redirect strips credential and origin carrying headers`() {
        val result = SensitiveHeaderPolicy.apply(
            headers = headersOf(
                "Authorization", "Bearer secret",
                "Proxy-Authorization", "Basic secret",
                "Cookie", "session=secret",
                "Cookie2", "legacy=secret",
                "Referer", "https://provider.example/setup",
                "Origin", "https://provider.example",
                "X-Api-Key", "api-secret",
                "X-Auth-Token", "auth-secret",
                "X-Access-Token", "access-secret",
                "User-Agent", "MuxTV/1",
                "Accept", "application/x-mpegURL",
                "X-Provider-Id", "provider-42",
            ),
            disposition = RedirectHeaderDisposition.StripSensitive,
        )

        assertThat(result.names()).containsExactly(
            "Accept",
            "User-Agent",
            "X-Provider-Id",
        )
        assertThat(result["User-Agent"]).isEqualTo("MuxTV/1")
        assertThat(result["Accept"]).isEqualTo("application/x-mpegURL")
        assertThat(result["X-Provider-Id"]).isEqualTo("provider-42")
    }

    @Test
    fun `sensitive header matching is case insensitive`() {
        val result = SensitiveHeaderPolicy.apply(
            headers = headersOf(
                "authorization", "Bearer secret",
                "x-API-key", "api-secret",
                "USER-AGENT", "MuxTV/1",
            ),
            disposition = RedirectHeaderDisposition.StripSensitive,
        )

        assertThat(result.names()).containsExactly("USER-AGENT")
    }
}

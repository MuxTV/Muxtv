package app.muxtv.catalog.refresh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteSourceAccessCodecTest {
    @Test
    fun `round trips access descriptor without exposing secret values`() {
        val access = RemoteSourceAccess(
            url = "https://provider.example/list.m3u?token=secret-token",
            insecureHttpApproved = false,
            userAgent = "Provider Agent",
            referrer = "https://portal.example/",
            sensitiveHeaders = mapOf(
                "authorization" to "Bearer private-value",
                "x-api-key" to "private-api-key",
            ),
        )

        val decoded = RemoteSourceAccessCodec.encode(access).use(RemoteSourceAccessCodec::decode)

        assertThat(decoded.url).isEqualTo(access.url)
        assertThat(decoded.userAgent).isEqualTo("Provider Agent")
        assertThat(decoded.referrer).isEqualTo("https://portal.example/")
        assertThat(decoded.sensitiveHeaders).containsExactly(
            "Authorization",
            "Bearer private-value",
            "X-Api-Key",
            "private-api-key",
        )
        assertThat(access.toString()).doesNotContain("secret-token")
        assertThat(access.toString()).doesNotContain("private-value")
        assertThat(access.toString()).doesNotContain("private-api-key")
    }
}

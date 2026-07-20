package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class RedirectPolicyTest {
    @Test
    fun `relative same-origin https redirect preserves headers`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "https://provider.example/path/list.m3u".toHttpUrl(),
            location = "../next.m3u?token=abc",
            completedRedirects = 0,
            insecureHttpApproved = false,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Follow(
                targetUrl = "https://provider.example/next.m3u?token=abc".toHttpUrl(),
                headerDisposition = RedirectHeaderDisposition.Preserve,
            ),
        )
    }

    @Test
    fun `cross-origin https redirect strips sensitive headers`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "https://provider.example/list.m3u".toHttpUrl(),
            location = "https://cdn.example/list.m3u",
            completedRedirects = 1,
            insecureHttpApproved = false,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Follow(
                targetUrl = "https://cdn.example/list.m3u".toHttpUrl(),
                headerDisposition = RedirectHeaderDisposition.StripSensitive,
            ),
        )
    }

    @Test
    fun `https downgrade is rejected even when insecure http was approved`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "https://provider.example/list.m3u".toHttpUrl(),
            location = "http://provider.example/list.m3u",
            completedRedirects = 0,
            insecureHttpApproved = true,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.HttpsDowngrade),
        )
    }

    @Test
    fun `http redirect requires prior insecure transport approval`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "http://provider.example/list.m3u".toHttpUrl(),
            location = "/next.m3u",
            completedRedirects = 0,
            insecureHttpApproved = false,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.InsecureTransportNotApproved),
        )
    }

    @Test
    fun `approved same-origin http redirect preserves headers`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "http://provider.example/list.m3u".toHttpUrl(),
            location = "/next.m3u",
            completedRedirects = 0,
            insecureHttpApproved = true,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Follow(
                targetUrl = "http://provider.example/next.m3u".toHttpUrl(),
                headerDisposition = RedirectHeaderDisposition.Preserve,
            ),
        )
    }

    @Test
    fun `redirect hop budget is five`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "https://provider.example/list.m3u".toHttpUrl(),
            location = "/next.m3u",
            completedRedirects = RedirectPolicy.MAX_REDIRECTS,
            insecureHttpApproved = false,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.TooManyRedirects),
        )
    }

    @Test
    fun `redirect target with credentials or fragment is rejected`() {
        val currentUrl = "https://provider.example/list.m3u".toHttpUrl()

        assertThat(
            RedirectPolicy.evaluate(currentUrl, "https://alice:secret@cdn.example/list.m3u", 0, false),
        ).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.EmbeddedCredentials),
        )
        assertThat(
            RedirectPolicy.evaluate(currentUrl, "/next.m3u#token", 0, false),
        ).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.Fragment),
        )
    }

    @Test
    fun `encoded control separator in redirect location is rejected`() {
        val decision = RedirectPolicy.evaluate(
            currentUrl = "https://provider.example/list.m3u".toHttpUrl(),
            location = "/next.m3u?token=abc%250d%250aAuthorization%3Aevil",
            completedRedirects = 0,
            insecureHttpApproved = false,
        )

        assertThat(decision).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.ControlSeparator),
        )
    }

    @Test
    fun `blank and malformed locations are rejected without throwing`() {
        val currentUrl = "https://provider.example/list.m3u".toHttpUrl()

        assertThat(RedirectPolicy.evaluate(currentUrl, " ", 0, false)).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.MissingLocation),
        )
        assertThat(RedirectPolicy.evaluate(currentUrl, "https://[broken", 0, false)).isEqualTo(
            RedirectDecision.Rejected(RedirectRejectionReason.MalformedLocation),
        )
    }
}

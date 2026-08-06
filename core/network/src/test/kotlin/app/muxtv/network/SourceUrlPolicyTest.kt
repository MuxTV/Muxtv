package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceUrlPolicyTest {
    @Test
    fun `bare host becomes an HTTPS candidate`() {
        val result = SourceUrlPolicy.evaluate(
            "  provider.example/playlist.m3u?token=abc123  ",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Allowed(
                normalizedUrl = "https://provider.example/playlist.m3u?token=abc123",
            ),
        )
    }

    @Test
    fun `bare host with port becomes an HTTPS candidate`() {
        val result = SourceUrlPolicy.evaluate(
            "provider.example:8443/playlist.m3u",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Allowed(
                normalizedUrl = "https://provider.example:8443/playlist.m3u",
            ),
        )
    }

    @Test
    fun `mixed case HTTP schemes are canonicalized without changing transport policy`() {
        assertThat(SourceUrlPolicy.evaluate("HTTPS://provider.example/playlist.m3u")).isEqualTo(
            SourceUrlDecision.Allowed(
                normalizedUrl = "https://provider.example/playlist.m3u",
            ),
        )
        assertThat(SourceUrlPolicy.evaluate("Http://provider.example/playlist.m3u")).isEqualTo(
            SourceUrlDecision.RequiresInsecureTransportApproval(
                normalizedUrl = "http://provider.example/playlist.m3u",
            ),
        )
    }

    @Test
    fun `explicit non HTTP schemes are never rewritten as HTTPS`() {
        assertThat(SourceUrlPolicy.evaluate("content://playlist/source")).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.UnsupportedScheme),
        )
        assertThat(SourceUrlPolicy.evaluate("file:///sdcard/playlist.m3u")).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.UnsupportedScheme),
        )
    }

    @Test
    fun `malformed pseudo scheme is rejected instead of becoming a host`() {
        assertThat(SourceUrlPolicy.evaluate("ht!tp://provider.example/playlist.m3u")).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.Malformed),
        )
        assertThat(SourceUrlPolicy.evaluate("://provider.example/playlist.m3u")).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.Malformed),
        )
    }

    @Test
    fun `https source is allowed`() {
        val result = SourceUrlPolicy.evaluate(
            "https://provider.example:8443/playlist.m3u?token=abc123",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Allowed(
                normalizedUrl = "https://provider.example:8443/playlist.m3u?token=abc123",
            ),
        )
    }

    @Test
    fun `http source requires explicit approval`() {
        val result = SourceUrlPolicy.evaluate(
            "http://provider.example/playlist.m3u",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.RequiresInsecureTransportApproval(
                normalizedUrl = "http://provider.example/playlist.m3u",
            ),
        )
    }

    @Test
    fun `embedded authority credentials are rejected`() {
        val result = SourceUrlPolicy.evaluate(
            "https://alice:secret@provider.example/playlist.m3u",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.EmbeddedCredentials),
        )
    }

    @Test
    fun `double encoded control separator is rejected`() {
        val result = SourceUrlPolicy.evaluate(
            "https://provider.example/playlist.m3u?token=abc%250d%250aAuthorization%3Aevil",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.ControlSeparator),
        )
    }

    @Test
    fun `fragment is rejected`() {
        val result = SourceUrlPolicy.evaluate(
            "https://provider.example/playlist.m3u#credentials",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.Fragment),
        )
    }

    @Test
    fun `blank and malformed sources are rejected without throwing`() {
        assertThat(SourceUrlPolicy.evaluate(" ")).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.Empty),
        )
        assertThat(SourceUrlPolicy.evaluate("https://[broken")).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.Malformed),
        )
    }
}

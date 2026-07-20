package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceUrlPolicyTest {
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
    fun `non HTTP scheme is rejected`() {
        val result = SourceUrlPolicy.evaluate(
            "file:///sdcard/playlist.m3u",
        )

        assertThat(result).isEqualTo(
            SourceUrlDecision.Rejected(SourceUrlRejectionReason.UnsupportedScheme),
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

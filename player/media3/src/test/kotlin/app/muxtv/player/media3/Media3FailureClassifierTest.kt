package app.muxtv.player.media3

import androidx.media3.common.PlaybackException
import app.muxtv.player.PlaybackFailureCategory
import com.google.common.truth.Truth.assertThat
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test

class Media3FailureClassifierTest {
    @Test
    fun `cause family takes precedence without retaining exception text`() {
        val dns = Media3FailureClassifier.classify(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            UnknownHostException("https://secret.example/live?token=private"),
        )
        val tls = Media3FailureClassifier.classify(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            SSLHandshakeException("certificate for secret.example"),
        )

        assertThat(dns.category).isEqualTo(PlaybackFailureCategory.DNS)
        assertThat(tls.category).isEqualTo(PlaybackFailureCategory.TLS)
        assertThat(dns.toString()).doesNotContain("secret.example")
    }

    @Test
    fun `media3 codes map to actionable bounded families`() {
        assertThat(
            Media3FailureClassifier.classify(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ).category,
        ).isEqualTo(PlaybackFailureCategory.TIMEOUT)
        assertThat(
            Media3FailureClassifier.classify(
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            ).category,
        ).isEqualTo(PlaybackFailureCategory.MANIFEST_FORMAT)
        assertThat(
            Media3FailureClassifier.classify(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            ).category,
        ).isEqualTo(PlaybackFailureCategory.CODEC_DECODER)
        assertThat(
            Media3FailureClassifier.classify(
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            ).category,
        ).isEqualTo(PlaybackFailureCategory.REDIRECT_POLICY)
        assertThat(
            Media3FailureClassifier.classify(
                PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ).category,
        ).isEqualTo(PlaybackFailureCategory.PLAYER_RENDER)
        assertThat(
            Media3FailureClassifier.classify(
                PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            ).category,
        ).isEqualTo(PlaybackFailureCategory.CREDENTIAL_ACCESS)
    }
}

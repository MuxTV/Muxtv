package app.muxtv.player.media3

import androidx.media3.common.PlaybackException
import app.muxtv.player.PlaybackErrorCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3ErrorMappingTest {
    @Test
    fun `network timeout maps to stable MuxTV error`() {
        val error = Media3ErrorMapper.fromCode(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        assertThat(error.code).isEqualTo(PlaybackErrorCode.NETWORK_TIMEOUT)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `decoder initialization failure maps to non retryable decoder error`() {
        val error = Media3ErrorMapper.fromCode(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )

        assertThat(error.code).isEqualTo(PlaybackErrorCode.UNSUPPORTED_FORMAT)
        assertThat(error.retryable).isFalse()
    }
}

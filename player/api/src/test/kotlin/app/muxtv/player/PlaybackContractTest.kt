package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackContractTest {
    @Test
    fun `public error codes are stable and do not expose engine exceptions`() {
        val error = PlaybackError(
            code = PlaybackErrorCode.NETWORK_TIMEOUT,
            message = "Источник не ответил вовремя",
            retryable = true,
        )
        assertThat(error.code.externalName).isEqualTo("network_timeout")
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `track selection uses semantic track identity`() {
        val track = PlaybackTrack(TrackId("audio-rus-ac3"), PlaybackTrackKind.AUDIO, "ru", "AC-3")
        assertThat(track.id.value).isEqualTo("audio-rus-ac3")
    }
}

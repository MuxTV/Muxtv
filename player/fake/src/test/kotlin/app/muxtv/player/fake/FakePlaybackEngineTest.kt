package app.muxtv.player.fake

import app.muxtv.player.PlaybackRequest
import app.muxtv.player.PlaybackState
import app.muxtv.player.StreamVariantId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakePlaybackEngineTest {
    @Test
    fun `fake emits deterministic first frame state`() = runTest {
        val engine = FakePlaybackEngine()
        engine.prepare(PlaybackRequest(StreamVariantId("variant-1"), "https://example/live.m3u8"))
        engine.emitFirstFrame()
        assertThat(engine.state.value).isEqualTo(PlaybackState.Playing)
    }
}

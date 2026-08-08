package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackAttemptTokenTest {
    private val candidate = PlaybackCandidateIdentity("channel-news", "variant-a")

    @Test
    fun `token matches only its setup generation and candidate`() {
        val token = PlaybackAttemptToken(setupId("setup-a"), 7L, candidate)

        assertThat(token.matches(setupId("setup-a"), 7L, candidate)).isTrue()
        assertThat(token.matches(setupId("setup-b"), 7L, candidate)).isFalse()
        assertThat(token.matches(setupId("setup-a"), 8L, candidate)).isFalse()
        assertThat(
            token.matches(
                setupId("setup-a"),
                7L,
                candidate.copy(variantId = "variant-b"),
            ),
        ).isFalse()
    }

    @Test
    fun `callback gate accepts an attempt exactly once`() {
        val token = PlaybackAttemptToken(setupId("setup-a"), 7L, candidate)
        val gate = PlaybackCallbackGate()
        gate.activate(token)

        assertThat(gate.consume(token)).isTrue()
        assertThat(gate.consume(token)).isFalse()
        assertThat(gate.isCurrent(token)).isFalse()
    }

    private fun setupId(raw: String): PlaybackSetupId = requireNotNull(PlaybackSetupId.parse(raw))
}

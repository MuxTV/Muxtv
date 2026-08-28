package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackControlSessionContractTest {
    @Test
    fun timelineUsesNullableDurationInsteadOfEngineSentinel() {
        val unknown = PlaybackTimelineState(
            positionMs = 12_000L,
            durationMs = null,
            isLive = true,
        )

        assertThat(unknown.hasKnownDuration).isFalse()
        assertThat(unknown.durationMs).isNull()
    }

    @Test
    fun invalidTimelineValuesAreRejectedAtStableBoundary() {
        expectFailure<IllegalArgumentException> {
            PlaybackTimelineState(positionMs = -1L, durationMs = null, isLive = false)
        }
        expectFailure<IllegalArgumentException> {
            PlaybackTimelineState(positionMs = 0L, durationMs = 0L, isLive = false)
        }
    }

    @Test
    fun seekResultCarriesSemanticDirectionWithoutTransportIdentity() {
        val result = PlaybackSeekResult.Accepted(
            targetMs = 42_000L,
            direction = PlaybackSeekDirection.FORWARD,
        )

        assertThat(result.targetMs).isEqualTo(42_000L)
        assertThat(result.direction.sign).isEqualTo(1)
        assertThat(result.toString()).doesNotContain("mediaId")
        assertThat(result.toString()).doesNotContain("generation")
    }

    @Test
    fun playingSnapshotRequiresReadyPhase() {
        val capabilities = PlayerCapabilities(
            canSeek = false,
            canPause = true,
            canSetTrackSelection = false,
            hasAudioTracks = false,
            hasTextTracks = false,
            supportsFavorite = false,
            hasKnownDuration = false,
            isLive = true,
        )

        expectFailure<IllegalArgumentException> {
            PlaybackSessionSnapshot(
                phase = PlaybackSessionPhase.BUFFERING,
                isPlaying = true,
                hasError = false,
                capabilities = capabilities,
                timeline = PlaybackTimelineState(0L, null, isLive = true),
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                subtitlesDisabled = false,
            )
        }
    }

    @Test
    fun operationFailureTerminologyIsEngineNeutral() {
        val error = PlaybackSessionOperationException(
            PlaybackSessionOperationFailure.CommandTimedOut,
        )

        assertThat(error.message).contains("Playback session")
        assertThat(error.message).doesNotContain("MediaController")
        assertThat(error.failure).isEqualTo(PlaybackSessionOperationFailure.CommandTimedOut)
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        val error = runCatching(block).exceptionOrNull()
        assertThat(error).isInstanceOf(T::class.java)
        @Suppress("UNCHECKED_CAST")
        return error as T
    }
}

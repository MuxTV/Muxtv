package app.muxtv.player.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSeekTokenProjectionInstrumentedTest {
    @Test
    fun sameMediaIdWithDifferentInstallGenerationsProjectsDifferentTokens() {
        val first = mediaItem(mediaId = "channel-1", generation = 41L)
        val replacement = mediaItem(mediaId = "channel-1", generation = 42L)

        assertThat(first.playbackSeekToken())
            .isEqualTo(PlaybackSeekToken(mediaId = "channel-1", generation = 41L))
        assertThat(replacement.playbackSeekToken())
            .isEqualTo(PlaybackSeekToken(mediaId = "channel-1", generation = 42L))
        assertThat(first.playbackSeekToken()).isNotEqualTo(replacement.playbackSeekToken())
    }

    @Test
    fun missingOrInvalidGenerationFailsClosed() {
        val withoutGeneration = MediaItem.Builder()
            .setMediaId("channel-1")
            .build()
        val invalidGeneration = mediaItem(mediaId = "channel-1", generation = 0L)

        assertThat(withoutGeneration.playbackSeekToken()).isNull()
        assertThat(invalidGeneration.playbackSeekToken()).isNull()
    }

    private fun mediaItem(mediaId: String, generation: Long): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setExtras(
                android.os.Bundle().apply {
                    putLong(PLAYBACK_SEEK_GENERATION_EXTRA, generation)
                },
            )
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }
}

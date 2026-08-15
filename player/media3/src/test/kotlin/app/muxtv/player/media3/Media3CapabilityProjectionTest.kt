package app.muxtv.player.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3CapabilityProjectionTest {
    @Test
    fun `live stream without duration hides timeline and exposes seek command`() {
        val capabilities = derivePlayerCapabilities(
            availableCommands = setOf(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            ),
            tracks = Tracks.EMPTY,
            durationMs = C.TIME_UNSET,
            isLive = true,
            favoriteSupported = false,
        )

        assertThat(capabilities.isLive).isTrue()
        assertThat(capabilities.hasKnownDuration).isFalse()
        assertThat(capabilities.canSeek).isTrue()
        assertThat(capabilities.canPause).isTrue()
        assertThat(capabilities.canSetTrackSelection).isTrue()
        assertThat(capabilities.supportsFavorite).isFalse()
    }

    @Test
    fun `vod with known duration exposes timeline`() {
        val capabilities = derivePlayerCapabilities(
            availableCommands = emptySet(),
            tracks = Tracks.EMPTY,
            durationMs = 7_500_000L,
            isLive = false,
            favoriteSupported = true,
        )

        assertThat(capabilities.isLive).isFalse()
        assertThat(capabilities.hasKnownDuration).isTrue()
        assertThat(capabilities.canSeek).isFalse()
        assertThat(capabilities.supportsFavorite).isTrue()
    }

    @Test
    fun `tracks derive audio and text presence`() {
        val audioGroup = Tracks.Group(
            TrackGroup(audioFormat("audio/mp4a-latm", "first")),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
        val textGroup = Tracks.Group(
            TrackGroup(audioFormat("text/vtt", "captions")),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
        val tracks = Tracks(listOf(audioGroup, textGroup))

        val capabilities = derivePlayerCapabilities(
            availableCommands = setOf(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS),
            tracks = tracks,
            durationMs = C.TIME_UNSET,
            isLive = false,
            favoriteSupported = false,
        )

        assertThat(capabilities.hasAudioTracks).isTrue()
        assertThat(capabilities.hasTextTracks).isTrue()
        assertThat(capabilities.canSetTrackSelection).isTrue()
    }

    @Test
    fun `empty tracks report no track capability`() {
        val capabilities = derivePlayerCapabilities(
            availableCommands = emptySet(),
            tracks = Tracks.EMPTY,
            durationMs = 1_000L,
            isLive = false,
            favoriteSupported = false,
        )

        assertThat(capabilities.hasAudioTracks).isFalse()
        assertThat(capabilities.hasTextTracks).isFalse()
    }

    @Test
    fun `missing set track selection command disables track actions`() {
        val capabilities = derivePlayerCapabilities(
            availableCommands = setOf(Player.COMMAND_PLAY_PAUSE),
            tracks = Tracks.EMPTY,
            durationMs = C.TIME_UNSET,
            isLive = false,
            favoriteSupported = false,
        )

        assertThat(capabilities.canSetTrackSelection).isFalse()
    }

    @Test
    fun `null tracks snapshot behaves like empty`() {
        val capabilities = derivePlayerCapabilities(
            availableCommands = emptySet(),
            tracks = null,
            durationMs = C.TIME_UNSET,
            isLive = false,
            favoriteSupported = false,
        )

        assertThat(capabilities.hasAudioTracks).isFalse()
        assertThat(capabilities.hasTextTracks).isFalse()
    }

    private fun audioFormat(mimeType: String, id: String): Format =
        Format.Builder().setId(id).setSampleMimeType(mimeType).build()
}

package app.muxtv.player.media3

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvSessionSeekPolicyTest {
    @Test
    fun `relative intents preserve the existing service direction policy`() {
        assertThat(
            MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_BACKWARD),
        ).isEqualTo(
            MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_BACKWARD),
        )
        assertThat(
            MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_FORWARD),
        ).isEqualTo(
            MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_FORWARD),
        )
    }

    @Test
    fun `current item absolute seek preserves the requested target`() {
        assertThat(
            muxTvAbsoluteSessionSeekIntent(
                currentMediaItemIndex = 3,
                requestedMediaItemIndex = null,
                targetMs = 42_000L,
            ),
        ).isEqualTo(MuxTvSessionSeekIntent.Absolute(targetMs = 42_000L))
    }

    @Test
    fun `indexed absolute seek is accepted only for the current media item`() {
        assertThat(
            muxTvAbsoluteSessionSeekIntent(
                currentMediaItemIndex = 3,
                requestedMediaItemIndex = 3,
                targetMs = 42_000L,
            ),
        ).isEqualTo(MuxTvSessionSeekIntent.Absolute(targetMs = 42_000L))
        assertThat(
            muxTvAbsoluteSessionSeekIntent(
                currentMediaItemIndex = 3,
                requestedMediaItemIndex = 4,
                targetMs = 42_000L,
            ),
        ).isNull()
    }

    @Test
    fun `negative and time-unset-like targets are not synthesized`() {
        assertThat(
            muxTvAbsoluteSessionSeekIntent(
                currentMediaItemIndex = 0,
                requestedMediaItemIndex = null,
                targetMs = Long.MIN_VALUE,
            ),
        ).isNull()
        assertThat(
            muxTvAbsoluteSessionSeekIntent(
                currentMediaItemIndex = 0,
                requestedMediaItemIndex = null,
                targetMs = -1L,
            ),
        ).isNull()
    }

    @Test
    fun `unsupported playlist and default-position commands are filtered`() {
        val source = Player.Commands.Builder()
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .build()

        val filtered = filteredMuxTvSessionCommands(source)

        assertThat(filtered.contains(Player.COMMAND_SEEK_BACK)).isTrue()
        assertThat(filtered.contains(Player.COMMAND_SEEK_FORWARD)).isTrue()
        assertThat(filtered.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)).isTrue()
        blockedMuxTvSessionSeekCommands.forEach { command ->
            assertThat(filtered.contains(command)).isFalse()
        }
    }
}

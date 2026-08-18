package app.muxtv.player.media3

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvSessionSeekPolicyTest {
    @Test
    fun `standard back and forward map to the service relative policy`() {
        assertThat(muxTvSessionSeekIntent(Player.COMMAND_SEEK_BACK, positionMs = 50_000L))
            .isEqualTo(
                MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_BACKWARD),
            )
        assertThat(muxTvSessionSeekIntent(Player.COMMAND_SEEK_FORWARD, positionMs = 50_000L))
            .isEqualTo(
                MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_FORWARD),
            )
    }

    @Test
    fun `current item absolute seek preserves the requested target`() {
        assertThat(
            muxTvSessionSeekIntent(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                positionMs = 42_000L,
            ),
        ).isEqualTo(MuxTvSessionSeekIntent.Absolute(targetMs = 42_000L))
    }

    @Test
    fun `negative or default-position absolute targets are not synthesized`() {
        assertThat(
            muxTvSessionSeekIntent(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                positionMs = Long.MIN_VALUE,
            ),
        ).isNull()
        assertThat(
            muxTvSessionSeekIntent(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                positionMs = -1L,
            ),
        ).isNull()
    }

    @Test
    fun `playlist and default-position seek commands are not part of the authority contract`() {
        assertThat(blockedMuxTvSessionSeekCommands).containsExactly(
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
        )
        blockedMuxTvSessionSeekCommands.forEach { command ->
            assertThat(muxTvSessionSeekIntent(command, positionMs = 12_345L)).isNull()
        }
    }
}

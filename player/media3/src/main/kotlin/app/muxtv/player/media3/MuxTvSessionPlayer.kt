package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Semantic current-item seek emitted by standard Media3 [Player] controls.
 *
 * Unlike the private generation-aware session command, a standard Player command cannot carry the
 * playback token. The service therefore binds this intent to its current token at handling time and
 * still routes it through the same [PlaybackSeekController].
 */
internal sealed interface MuxTvSessionSeekIntent {
    data class Relative(val direction: Int) : MuxTvSessionSeekIntent

    data class Absolute(val targetMs: Long) : MuxTvSessionSeekIntent
}

internal val blockedMuxTvSessionSeekCommands: Set<Int> = setOf(
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
)

internal fun muxTvSessionSeekIntent(
    seekCommand: Int,
    positionMs: Long,
): MuxTvSessionSeekIntent? = when (seekCommand) {
    Player.COMMAND_SEEK_BACK ->
        MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_BACKWARD)
    Player.COMMAND_SEEK_FORWARD ->
        MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_FORWARD)
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM ->
        positionMs.takeIf { it >= 0L }?.let(MuxTvSessionSeekIntent::Absolute)
    else -> null
}

/**
 * Session-facing Player adapter that closes the standard Media3 seek bypass.
 *
 * Raw [Player] state is forwarded, but all advertised current-item seek operations are normalized
 * into [MuxTvSessionSeekIntent] and handed back to [MuxTvPlaybackService]. The adapter never calls
 * the delegate seek methods. Playlist/default-position seek variants are deliberately not
 * advertised because the #132 semantic contract models current-item relative/absolute seek only.
 */
@AndroidXOptIn(UnstableApi::class)
internal class MuxTvSessionPlayer(
    player: Player,
    private val onSeekIntent: (MuxTvSessionSeekIntent) -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
        blockedMuxTvSessionSeekCommands.forEach { command -> commands.remove(command) }
        return state.buildUpon()
            .setAvailableCommands(commands.build())
            .build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        muxTvSessionSeekIntent(seekCommand, positionMs)?.let(onSeekIntent)
        return Futures.immediateVoidFuture()
    }
}

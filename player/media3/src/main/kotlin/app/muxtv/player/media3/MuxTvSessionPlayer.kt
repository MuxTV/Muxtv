package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.IdentityHashMap

/**
 * Semantic current-item seek emitted by standard Media3 [Player] controls.
 *
 * Standard Player commands cannot carry MuxTV's private playback-generation token, so the service
 * binds these intents to its current token at handling time before they enter the shared seek
 * authority.
 */
internal sealed interface MuxTvSessionSeekIntent {
    data class Relative(val direction: Int) : MuxTvSessionSeekIntent

    data class Absolute(val targetMs: Long) : MuxTvSessionSeekIntent
}

/**
 * Seek commands intentionally not represented by issue #132's current-item relative/absolute
 * contract. They are filtered from the session-facing Player instead of being guessed or forwarded
 * to the raw ExoPlayer.
 */
internal val blockedMuxTvSessionSeekCommands: Set<Int> = setOf(
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
)

@AndroidXOptIn(UnstableApi::class)
internal fun filteredMuxTvSessionCommands(commands: Player.Commands): Player.Commands =
    commands.buildUpon().apply {
        blockedMuxTvSessionSeekCommands.forEach { command -> remove(command) }
    }.build()

@AndroidXOptIn(UnstableApi::class)
internal fun filteredMuxTvSessionEvents(events: Player.Events): Player.Events {
    val flags = FlagSet.Builder()
    for (index in 0 until events.size()) {
        val event = events.get(index)
        if (event != Player.EVENT_AVAILABLE_COMMANDS_CHANGED) flags.add(event)
    }
    return Player.Events(flags.build())
}

internal fun muxTvAbsoluteSessionSeekIntent(
    currentMediaItemIndex: Int,
    requestedMediaItemIndex: Int?,
    targetMs: Long,
): MuxTvSessionSeekIntent.Absolute? {
    if (targetMs < 0L) return null
    if (requestedMediaItemIndex != null && requestedMediaItemIndex != currentMediaItemIndex) {
        return null
    }
    return MuxTvSessionSeekIntent.Absolute(targetMs)
}

/**
 * Session-facing Player adapter that closes the standard Media3 seek bypass.
 *
 * The wrapper deliberately keeps the delegate's observable playback state untouched. Standard
 * current-item seek methods are intercepted and normalized into the service authority, while the
 * actual position/discontinuity remains owned by the raw ExoPlayer when the coalesced seek is
 * finally applied. This avoids a second optimistic Player state machine in front of the service.
 *
 * ForwardingPlayer requires listener callbacks to stay consistent when command availability is
 * narrowed. This adapter therefore filters both the synchronous command queries and the matching
 * listener/event callbacks. Default-position / playlist navigation seek methods also remain
 * defensive no-ops. The result is one coherent session-facing Player contract without forwarding a
 * hidden seek mutation to ExoPlayer.
 */
@AndroidXOptIn(UnstableApi::class)
internal class MuxTvSessionPlayer(
    player: Player,
    private val onSeekIntent: (MuxTvSessionSeekIntent) -> Unit,
) : ForwardingPlayer(player) {
    private val filteredListeners = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun getAvailableCommands(): Player.Commands =
        filteredMuxTvSessionCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        command !in blockedMuxTvSessionSeekCommands && super.isCommandAvailable(command)

    override fun addListener(listener: Player.Listener) {
        val forwardingListener = synchronized(filteredListeners) {
            filteredListeners[listener] ?: createCommandFilteringListener(listener).also {
                filteredListeners[listener] = it
            }
        }
        super.addListener(forwardingListener)
    }

    override fun removeListener(listener: Player.Listener) {
        val forwardingListener = synchronized(filteredListeners) {
            filteredListeners.remove(listener)
        } ?: listener
        super.removeListener(forwardingListener)
    }

    override fun seekBack() {
        onSeekIntent(MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_BACKWARD))
    }

    override fun seekForward() {
        onSeekIntent(MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_FORWARD))
    }

    override fun seekTo(positionMs: Long) {
        muxTvAbsoluteSessionSeekIntent(
            currentMediaItemIndex = currentMediaItemIndex,
            requestedMediaItemIndex = null,
            targetMs = positionMs,
        )?.let(onSeekIntent)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        muxTvAbsoluteSessionSeekIntent(
            currentMediaItemIndex = currentMediaItemIndex,
            requestedMediaItemIndex = mediaItemIndex,
            targetMs = positionMs,
        )?.let(onSeekIntent)
    }

    override fun seekToDefaultPosition() = Unit

    override fun seekToDefaultPosition(mediaItemIndex: Int) = Unit

    override fun seekToNext() = Unit

    override fun seekToNextMediaItem() = Unit

    override fun seekToPrevious() = Unit

    override fun seekToPreviousMediaItem() = Unit

    private fun createCommandFilteringListener(listener: Player.Listener): Player.Listener {
        var visibleCommands = getAvailableCommands()
        var visibleCommandsChangedSinceLastEvents = false
        return object : Player.Listener by listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                val filtered = filteredMuxTvSessionCommands(availableCommands)
                if (filtered == visibleCommands) return
                visibleCommands = filtered
                visibleCommandsChangedSinceLastEvents = true
                listener.onAvailableCommandsChanged(filtered)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val includesCommandChange =
                    events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                val forwardedEvents = if (
                    includesCommandChange && !visibleCommandsChangedSinceLastEvents
                ) {
                    filteredMuxTvSessionEvents(events)
                } else {
                    events
                }
                visibleCommandsChangedSinceLastEvents = false
                if (forwardedEvents.size() > 0) {
                    listener.onEvents(this@MuxTvSessionPlayer, forwardedEvents)
                }
            }
        }
    }
}

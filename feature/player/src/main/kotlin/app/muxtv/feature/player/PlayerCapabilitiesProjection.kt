package app.muxtv.feature.player

import android.os.Handler
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import app.muxtv.player.AudioTrackUiModel
import app.muxtv.player.PlayerCapabilities
import app.muxtv.player.SubtitleTrackUiModel
import app.muxtv.player.media3.Media3TrackController
import app.muxtv.player.media3.Media3TrackProjector
import app.muxtv.player.media3.derivePlayerCapabilities
import app.muxtv.player.media3.toIntSet

/**
 * Runs [block] on the controller's application looper when the caller is not already on it.
 * MediaController enforces that all API calls happen on its application thread; effect disposal
 * in tests can happen on a different thread, so registration and teardown are marshalled.
 */
internal fun MediaController.runOnApplicationThread(block: () -> Unit) {
    val looper = applicationLooper
    if (looper.thread === Thread.currentThread()) {
        block()
    } else {
        Handler(looper).post(block)
    }
}

/**
 * Capability projection of the current controller state, recomputed on every player event.
 * The UI never assumes capability from route kind, source name or persisted flags.
 *
 * Live-ness is timeline-derived. MediaItem.liveConfiguration contains live-offset overrides and is
 * not evidence that the current timeline window is actually live.
 */
@AndroidXOptIn(UnstableApi::class)
@Composable
fun rememberPlayerCapabilities(
    controller: MediaController,
    favoriteSupported: Boolean,
): PlayerCapabilities {
    fun snapshot(player: Player): PlayerCapabilities = derivePlayerCapabilities(
        availableCommands = player.availableCommands.toIntSet(),
        tracks = player.currentTracks,
        durationMs = player.duration,
        isLive = player.isCurrentMediaItemLive,
        favoriteSupported = favoriteSupported,
    )

    val state by produceState(
        initialValue = snapshot(controller),
        controller,
        favoriteSupported,
    ) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                value = snapshot(player)
            }
        }
        controller.runOnApplicationThread {
            controller.addListener(listener)
            // Re-read after listener registration so a timeline/command update cannot be lost
            // between the produceState initial snapshot and subscription.
            value = snapshot(controller)
        }
        awaitDispose { controller.runOnApplicationThread { controller.removeListener(listener) } }
    }
    return state
}

@AndroidXOptIn(UnstableApi::class)
@Composable
fun rememberAudioTrackModels(
    controller: MediaController,
): List<AudioTrackUiModel> {
    val projector = remember { Media3TrackProjector() }
    val state by produceState(
        initialValue = emptyList<AudioTrackUiModel>(),
        controller,
        projector,
    ) {
        fun rebuild(): List<AudioTrackUiModel> {
            val snapshot = Media3TrackController.snapshot(controller.trackSelectionParameters)
            return projector.audioTracks(
                tracks = controller.currentTracks,
                selectedGroup = snapshot.selectedAudioGroup,
                selectedIndices = snapshot.selectedAudioIndices,
            )
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TRACKS_CHANGED,
                        Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
                    )
                ) {
                    value = rebuild()
                }
            }
        }
        controller.runOnApplicationThread {
            controller.addListener(listener)
            value = rebuild()
        }
        awaitDispose { controller.runOnApplicationThread { controller.removeListener(listener) } }
    }
    return state
}

@AndroidXOptIn(UnstableApi::class)
@Composable
fun rememberSubtitleTrackModels(
    controller: MediaController,
): SubtitleTrackModels {
    val projector = remember { Media3TrackProjector() }
    val state by produceState(
        initialValue = SubtitleTrackModels(emptyList(), textDisabled = false),
        controller,
        projector,
    ) {
        fun rebuild(): SubtitleTrackModels {
            val snapshot = Media3TrackController.snapshot(controller.trackSelectionParameters)
            return SubtitleTrackModels(
                tracks = projector.textTracks(
                    tracks = controller.currentTracks,
                    selectedGroup = snapshot.selectedTextGroup,
                    selectedIndices = snapshot.selectedTextIndices,
                ),
                textDisabled = snapshot.textDisabled,
            )
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TRACKS_CHANGED,
                        Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
                    )
                ) {
                    value = rebuild()
                }
            }
        }
        controller.runOnApplicationThread {
            controller.addListener(listener)
            value = rebuild()
        }
        awaitDispose { controller.runOnApplicationThread { controller.removeListener(listener) } }
    }
    return state
}

data class SubtitleTrackModels(
    val tracks: List<SubtitleTrackUiModel>,
    val textDisabled: Boolean,
)

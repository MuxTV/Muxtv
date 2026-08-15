package app.muxtv.feature.player

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
 * Capability projection of the current controller state, recomputed on every player event.
 * The UI never assumes capability from route kind, source name or persisted flags.
 */
@AndroidXOptIn(UnstableApi::class)
@Composable
fun rememberPlayerCapabilities(
    controller: MediaController,
    favoriteSupported: Boolean,
): PlayerCapabilities {
    val state by produceState(
        initialValue = derivePlayerCapabilities(
            availableCommands = controller.availableCommands.toIntSet(),
            tracks = controller.currentTracks,
            durationMs = controller.duration,
            isLive = controller.currentMediaItem?.liveConfiguration != null,
            favoriteSupported = favoriteSupported,
        ),
        controller,
        favoriteSupported,
    ) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                value = derivePlayerCapabilities(
                    availableCommands = player.availableCommands.toIntSet(),
                    tracks = player.currentTracks,
                    durationMs = player.duration,
                    isLive = player.currentMediaItem?.liveConfiguration != null,
                    favoriteSupported = favoriteSupported,
                )
            }
        }
        controller.addListener(listener)
        awaitDispose { controller.removeListener(listener) }
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
        controller.addListener(listener)
        value = rebuild()
        awaitDispose { controller.removeListener(listener) }
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
        controller.addListener(listener)
        value = rebuild()
        awaitDispose { controller.removeListener(listener) }
    }
    return state
}

data class SubtitleTrackModels(
    val tracks: List<SubtitleTrackUiModel>,
    val textDisabled: Boolean,
)

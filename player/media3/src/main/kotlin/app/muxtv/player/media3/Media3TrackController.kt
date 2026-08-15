package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController

/**
 * Thin adapter that applies track selection through the standard Media3
 * [TrackSelectionParameters] surface.
 *
 * Track switching never recreates the media item, never re-prepares and never restarts the
 * player. Selection becomes authoritative in the UI only after Media3 reports updated
 * parameters/tracks through its own events.
 */
@AndroidXOptIn(UnstableApi::class)
object Media3TrackController {

    data class TrackSelectionSnapshot(
        val selectedAudioGroup: TrackGroup?,
        val selectedAudioIndices: Set<Int>,
        val selectedTextGroup: TrackGroup?,
        val selectedTextIndices: Set<Int>,
        val textDisabled: Boolean,
    ) {
        companion object {
            val EMPTY = TrackSelectionSnapshot(
                selectedAudioGroup = null,
                selectedAudioIndices = emptySet(),
                selectedTextGroup = null,
                selectedTextIndices = emptySet(),
                textDisabled = false,
            )
        }
    }

    fun canSetTrackSelection(controller: MediaController): Boolean =
        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS in controller.availableCommands

    fun selectAudioTrack(
        controller: MediaController,
        groupId: String,
        trackIndex: Int,
    ): Boolean {
        if (!canSetTrackSelection(controller)) return false
        val group = findGroup(controller, C.TRACK_TYPE_AUDIO, groupId) ?: return false
        if (trackIndex !in 0 until group.length) return false
        val params = controller.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
        controller.trackSelectionParameters = params
        return true
    }

    fun selectTextTrack(
        controller: MediaController,
        groupId: String,
        trackIndex: Int,
    ): Boolean {
        if (!canSetTrackSelection(controller)) return false
        val group = findGroup(controller, C.TRACK_TYPE_TEXT, groupId) ?: return false
        if (trackIndex !in 0 until group.length) return false
        val params = controller.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
            .build()
        controller.trackSelectionParameters = params
        return true
    }

    fun disableTextTracks(controller: MediaController): Boolean {
        if (!canSetTrackSelection(controller)) return false
        val params = controller.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        controller.trackSelectionParameters = params
        return true
    }

    fun snapshot(parameters: TrackSelectionParameters): TrackSelectionSnapshot {
        var audioGroup: TrackGroup? = null
        var audioIndices: Set<Int> = emptySet()
        var textGroup: TrackGroup? = null
        var textIndices: Set<Int> = emptySet()
        for ((group, override) in parameters.overrides) {
            when (override.type) {
                C.TRACK_TYPE_AUDIO -> {
                    audioGroup = group
                    audioIndices = override.trackIndices.toSet()
                }
                C.TRACK_TYPE_TEXT -> {
                    textGroup = group
                    textIndices = override.trackIndices.toSet()
                }
            }
        }
        return TrackSelectionSnapshot(
            selectedAudioGroup = audioGroup,
            selectedAudioIndices = audioIndices,
            selectedTextGroup = textGroup,
            selectedTextIndices = textIndices,
            textDisabled = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes,
        )
    }

    private fun findGroup(
        controller: MediaController,
        type: Int,
        groupId: String,
    ): TrackGroup? {
        var typeIndex = 0
        for (group in controller.currentTracks.groups) {
            if (group.type != type) continue
            val id = group.mediaTrackGroup.id?.takeIf { it.isNotBlank() } ?: "group-$typeIndex"
            if (id == groupId) {
                return group.mediaTrackGroup.takeIf { it.length > 0 }
            }
            typeIndex += 1
        }
        return null
    }
}

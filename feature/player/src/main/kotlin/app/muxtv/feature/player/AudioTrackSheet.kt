package app.muxtv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.muxtv.player.AudioTrackUiModel

/**
 * Full-text audio track selector over the shared [TrackSelectionSheet] infrastructure.
 * The callers provide the selection callback; PlayerSurfaceContent wires it to
 * Media3TrackController.selectAudioTrack.
 */
@Composable
fun AudioTrackSheet(
    models: List<AudioTrackUiModel>,
    onSelect: (AudioTrackUiModel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetTestTag: String = "player-audio-sheet",
    rowTestTagPrefix: String = "player-audio-track",
) {
    TrackSelectionSheet(
        title = "Аудиодорожка",
        items = models.map { model ->
            TrackSheetItem(
                id = model.key.groupId + "-" + model.key.trackIndex,
                primaryLabel = model.primaryLabel,
                languageLabel = model.languageLabel,
                technicalLabel = model.technicalLabel,
                selected = model.selected,
                enabled = model.supported,
            )
        },
        onSelect = { item ->
            models.firstOrNull { it.key.groupId + "-" + it.key.trackIndex == item.id }
                ?.let(onSelect)
        },
        onDismiss = onDismiss,
        modifier = modifier,
        sheetTestTag = sheetTestTag,
        rowTestTagPrefix = rowTestTagPrefix,
        emptyMessage = "Аудиодорожки недоступны.",
    )
}

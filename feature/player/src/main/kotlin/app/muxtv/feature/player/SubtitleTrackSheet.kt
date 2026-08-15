package app.muxtv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.muxtv.player.SubtitleTrackUiModel

/**
 * Subtitle selector over the same [TrackSelectionSheet] infrastructure. Adds the "Off" row and
 * reuses focus/back/selection semantics — no second track state machine.
 */
@Composable
fun SubtitleTrackSheet(
    models: List<SubtitleTrackUiModel>,
    textDisabled: Boolean,
    onSelect: (SubtitleTrackUiModel) -> Unit,
    onSelectOff: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetTestTag: String = "player-subtitle-sheet",
    rowTestTagPrefix: String = "player-subtitle-track",
) {
    val items = buildList {
        add(
            TrackSheetItem(
                id = OFF_ROW_ID,
                primaryLabel = "Выключить",
                languageLabel = null,
                technicalLabel = "",
                selected = textDisabled,
                enabled = true,
            ),
        )
        addAll(models.map { model ->
            TrackSheetItem(
                id = model.key.groupId + "-" + model.key.trackIndex,
                primaryLabel = model.primaryLabel,
                languageLabel = model.languageLabel,
                technicalLabel = model.technicalLabel,
                selected = !textDisabled && model.selected,
                enabled = model.supported,
            )
        })
    }
    TrackSelectionSheet(
        title = "Субтитры",
        items = items,
        onSelect = { item ->
            if (item.id == OFF_ROW_ID) {
                onSelectOff()
            } else {
                models.firstOrNull { it.key.groupId + "-" + it.key.trackIndex == item.id }
                    ?.let(onSelect)
            }
        },
        onDismiss = onDismiss,
        modifier = modifier,
        sheetTestTag = sheetTestTag,
        rowTestTagPrefix = rowTestTagPrefix,
        emptyMessage = "Встроенные субтитры недоступны.",
    )
}

private const val OFF_ROW_ID = "subtitle-off"

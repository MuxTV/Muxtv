package app.muxtv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens

data class TrackSheetItem(
    val id: String,
    val primaryLabel: String,
    val languageLabel: String?,
    val technicalLabel: String,
    val selected: Boolean,
    val enabled: Boolean,
)

/**
 * Shared full-text track selection sheet.
 *
 * Normative presentation rules:
 * - no one-line ellipsis, no maxLines cap on the primary label;
 * - soft wrap enabled, variable row height;
 * - technical line separate;
 * - unsupported rows stay visible but disabled;
 * - focus starts on the selected row;
 * - Back closes the sheet; selection does not close it.
 */
@Composable
fun TrackSelectionSheet(
    title: String,
    items: List<TrackSheetItem>,
    onSelect: (TrackSheetItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetTestTag: String,
    rowTestTagPrefix: String,
    emptyMessage: String,
) {
    BackHandler(enabled = true) { onDismiss() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .testTag(sheetTestTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(SHEET_WIDTH_FRACTION)
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .clip(RoundedCornerShape(TvTokens.Shape.detailsCorner))
                .background(TvTokens.Color.surfaceRaised)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.border,
                    shape = RoundedCornerShape(TvTokens.Shape.detailsCorner),
                )
                .padding(TvTokens.Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Start,
            )
            if (items.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TrackSheetList(
                    items = items,
                    onSelect = onSelect,
                    rowTestTagPrefix = rowTestTagPrefix,
                )
            }
        }
    }
}

@Composable
private fun TrackSheetList(
    items: List<TrackSheetItem>,
    onSelect: (TrackSheetItem) -> Unit,
    rowTestTagPrefix: String,
) {
    val focusRequesters = remember(items) {
        List(items.size) { FocusRequester() }
    }
    val initialFocusIndex = items.indexOfFirst { it.selected }
        .takeIf { it >= 0 }
        ?: items.indexOfFirst { it.enabled }

    LaunchedEffect(items) {
        if (initialFocusIndex >= 0) {
            withFrameNanos { }
            focusRequesters[initialFocusIndex].requestFocus()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.xSmall),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            TrackSheetRow(
                item = item,
                onClick = { onSelect(item) },
                modifier = Modifier
                    .focusRequester(focusRequesters[index])
                    .testTag("$rowTestTagPrefix-$index"),
            )
        }
    }
}

@Composable
private fun TrackSheetRow(
    item: TrackSheetItem,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = item.enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(
                vertical = TvTokens.Spacing.xSmall,
                horizontal = TvTokens.Spacing.small,
            ),
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (item.selected) "●" else "○",
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.primaryLabel,
                style = MaterialTheme.typography.bodyLarge,
                softWrap = true,
                color = if (item.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            item.languageLabel?.let { language ->
                Text(
                    text = language,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.technicalLabel.isNotBlank()) {
                Text(
                    text = item.technicalLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.enabled) {
                Text(
                    text = "⚠ Не поддерживается этим устройством",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val SHEET_WIDTH_FRACTION = 0.62f
private const val SHEET_HEIGHT_FRACTION = 0.85f

package app.muxtv.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.icon.MuxTvIcons

data class MuxTvNavigationRailItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val testTag: String,
)

/**
 * Lounge Light left navigation rail.
 *
 * Expansion is derived only from descendant focus. The application shell owns
 * a fixed 88dp content inset, so this composable may draw at 248dp while focused
 * without changing destination constraints. Selected destination remains a
 * persistent state independent from the currently focused rail item.
 */
@Composable
fun MuxTvNavigationRail(
    items: List<MuxTvNavigationRailItem>,
    onSelect: (String) -> Unit,
    railFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onRailFocusChanged: (Boolean) -> Unit = {},
) {
    var railFocused by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (railFocused) TvTokens.Size.railExpanded else TvTokens.Size.railCollapsed,
        animationSpec = tween(
            durationMillis = TvTokens.Motion.screenDurationMillis,
            easing = TvTokens.Motion.easeInOut,
        ),
        label = "navigationRailWidth",
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .onFocusChanged { state ->
                val hasFocus = state.hasFocus
                if (railFocused != hasFocus) {
                    railFocused = hasFocus
                    onRailFocusChanged(hasFocus)
                }
            }
            .focusGroup()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = TvTokens.Spacing.small, vertical = TvTokens.Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.xSmall),
    ) {
        RailBrandMark(expanded = railFocused)
        Spacer(Modifier.height(TvTokens.Spacing.small))
        items.forEach { item ->
            MuxTvNavigationRailItemView(
                item = item,
                expanded = railFocused,
                onClick = { onSelect(item.key) },
                modifier = Modifier
                    .testTag(item.testTag)
                    .then(
                        if (item.selected) {
                            Modifier.focusRequester(railFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
private fun RailBrandMark(expanded: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = TvTokens.Spacing.xSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MuxTvIcons.BrandMark,
            contentDescription = "MuxTV",
            modifier = Modifier.size(36.dp),
            tint = TvTokens.Color.accent,
        )
        if (expanded) {
            Spacer(Modifier.width(TvTokens.Spacing.small))
            Text(
                text = "MuxTV",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MuxTvNavigationRailItemView(
    item: MuxTvNavigationRailItem,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.buttonCorner)
    val background = when {
        focused -> TvTokens.Color.surfaceRaised
        item.selected -> TvTokens.Color.accentSoft
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .height(56.dp)
            .fillMaxWidth()
            .semantics {
                contentDescription = item.label
                selected = item.selected
            }
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) TvTokens.Focus.outlineWidth else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Tab, onClick = onClick)
            .focusable()
            .padding(horizontal = TvTokens.Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.selected) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(TvTokens.Spacing.small))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = when {
                focused -> TvTokens.Color.accentStrong
                item.selected -> TvTokens.Color.accentStrong
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Spacer(Modifier.width(TvTokens.Spacing.small))
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleLarge,
                color = if (item.selected) TvTokens.Color.accentStrong else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

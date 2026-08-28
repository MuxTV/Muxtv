package app.muxtv.designsystem.component

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

internal data class NavigationRailMetrics(
    val verticalPadding: Dp,
    val brandHeight: Dp,
    val brandToItemsGap: Dp,
    val itemHeight: Dp,
    val itemGap: Dp,
) {
    fun requiredHeight(itemCount: Int): Dp {
        require(itemCount >= 0)
        val betweenItems = (itemCount - 1).coerceAtLeast(0)
        return verticalPadding * 2f +
            brandHeight +
            brandToItemsGap +
            itemHeight * itemCount.toFloat() +
            itemGap * betweenItems.toFloat()
    }
}

private val NORMAL_RAIL_METRICS = NavigationRailMetrics(
    verticalPadding = 20.dp,
    brandHeight = 28.dp,
    brandToItemsGap = 28.dp,
    itemHeight = 36.dp,
    itemGap = 8.dp,
)

private val COMPACT_RAIL_METRICS = NavigationRailMetrics(
    verticalPadding = 12.dp,
    brandHeight = 24.dp,
    brandToItemsGap = 8.dp,
    itemHeight = 32.dp,
    itemGap = 4.dp,
)

internal fun navigationRailMetrics(
    availableHeight: Dp,
    itemCount: Int,
): NavigationRailMetrics =
    if (NORMAL_RAIL_METRICS.requiredHeight(itemCount) <= availableHeight) {
        NORMAL_RAIL_METRICS
    } else {
        COMPACT_RAIL_METRICS
    }

internal fun navigationRailWidth(railFocused: Boolean): Dp =
    if (railFocused) TvTokens.Size.railExpanded else TvTokens.Size.railCollapsed

/**
 * Lounge Light left navigation rail.
 *
 * Destination content keeps a fixed collapsed-rail reservation in the application shell.
 * The rail itself expands only while one of its descendants owns focus, so labels can be
 * revealed without changing destination constraints or causing a horizontal content jump.
 * Selected destination remains a persistent state independent from the focused rail item.
 *
 * Normal Lounge geometry is preserved whenever it fits. Low-height TV viewports switch to
 * a compact vertical profile derived from the actual item count so every top-level destination
 * is laid out before the focus graph is evaluated.
 *
 * System reduced-motion is authoritative: when platform animators are disabled, the transient
 * reveal snaps between collapsed and expanded widths while focus/selection tone and outline
 * continue to provide immediate non-motion feedback.
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
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val width by animateDpAsState(
        targetValue = navigationRailWidth(railFocused),
        animationSpec = if (motionEnabled) {
            tween(
                durationMillis = TvTokens.Motion.screenDurationMillis,
                easing = TvTokens.Motion.easeInOut,
            )
        } else {
            snap()
        },
        label = "navigationRailWidth",
    )

    BoxWithConstraints(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
    ) {
        val metrics = navigationRailMetrics(
            availableHeight = maxHeight,
            itemCount = items.size,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { state ->
                    val hasFocus = state.hasFocus
                    if (railFocused != hasFocus) {
                        railFocused = hasFocus
                        onRailFocusChanged(hasFocus)
                    }
                }
                .focusGroup()
                .background(TvTokens.Color.accentSoft2)
                .padding(
                    horizontal = 12.dp,
                    vertical = metrics.verticalPadding,
                ),
        ) {
            RailBrandMark(
                expanded = railFocused,
                height = metrics.brandHeight,
            )
            Spacer(Modifier.height(metrics.brandToItemsGap))
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Spacer(Modifier.height(metrics.itemGap))
                    }
                    MuxTvNavigationRailItemView(
                        item = item,
                        expanded = railFocused,
                        itemHeight = metrics.itemHeight,
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
    }
}

@Composable
private fun RailBrandMark(
    expanded: Boolean,
    height: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MuxTvIcons.BrandMark,
            contentDescription = if (expanded) null else "MuxTV",
            modifier = Modifier.size(18.dp),
            tint = TvTokens.Color.accent,
        )
        if (expanded) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "MuxTV",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
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
    itemHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        focused -> TvTokens.Color.surfaceRaised
        item.selected -> TvTokens.Color.accentSoft
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .height(itemHeight)
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
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = when {
                focused -> TvTokens.Color.accentStrong
                item.selected -> TvTokens.Color.accentStrong
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (item.selected) TvTokens.Color.accentStrong else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

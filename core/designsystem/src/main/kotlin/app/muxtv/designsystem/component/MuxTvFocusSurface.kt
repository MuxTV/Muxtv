package app.muxtv.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.muxtv.designsystem.TvTokens

/**
 * Shared dense-TV focus surface (Lounge Light L2/L3 model).
 *
 * Unfocused: matte surface + hairline edge. Focused: raised warm surface,
 * bronze outline, restrained soft shadow and optional draw-time scale (reserved
 * for card rails with stable neighbor geometry; rows/grid use scale 1f).
 * Activation remains owned by Compose clickable semantics without synthesized keys.
 */
@Composable
fun MuxTvFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = TvTokens.Shape.cardCorner,
    contentPadding: Dp = TvTokens.Spacing.medium,
    focusScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = tween(durationMillis = TvTokens.Motion.screenDurationMillis),
        label = "focusSurfaceScale",
    )
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                if (focused) TvTokens.Color.surfaceRaised else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (focused) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        ambientColor = Color(0x24000000),
                        spotColor = Color(0x1A000000),
                    )
                } else {
                    Modifier
                },
            )
            .border(
                width = if (focused) TvTokens.Focus.outlineWidth else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.borderVariant
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(contentPadding),
        content = content,
    )
}

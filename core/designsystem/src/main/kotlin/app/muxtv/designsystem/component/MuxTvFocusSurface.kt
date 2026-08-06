package app.muxtv.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.muxtv.designsystem.TvTokens

/**
 * Shared dense-TV focus surface.
 *
 * Focus feedback is deliberately immediate: repeated D-pad movement must not queue or wait for
 * scale/position animation. The component keeps its measured geometry stable and uses an outline
 * plus a neutral surface-tone change. Activation remains owned by Compose clickable semantics;
 * this wrapper does not synthesize DPAD_CENTER/ENTER events in preview-key handlers.
 */
@Composable
fun MuxTvFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.cardCorner)
    val background = if (focused) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = modifier
            .alpha(if (focused) TvTokens.Focus.focusedAlpha else TvTokens.Focus.unfocusedAlpha)
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) TvTokens.Focus.outlineWidth else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(TvTokens.Spacing.medium),
        content = content,
    )
}

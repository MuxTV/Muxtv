package app.muxtv.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens

/**
 * Lounge Light action button: neutral inset surface, bronze focus outline,
 * optional persistent selection marker. Geometry stays stable; focus adds
 * outline + tone without resizing neighbors.
 */
@Composable
fun MuxTvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val isSelected = selected
    val shape = RoundedCornerShape(TvTokens.Shape.buttonCorner)
    Button(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.selected = isSelected },
        enabled = enabled,
        shape = ButtonDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape,
            disabledShape = shape,
            focusedDisabledShape = shape,
        ),
        colors = ButtonDefaults.colors(
            containerColor = when {
                !enabled -> TvTokens.Color.surfaceInset
                focused -> TvTokens.Color.accentSoft
                selected -> TvTokens.Color.accentSoft2
                else -> TvTokens.Color.surfaceInset
            },
            contentColor = when {
                !enabled -> TvTokens.Color.textDisabled
                focused -> TvTokens.Color.accentStrong
                selected -> TvTokens.Color.accentStrong
                else -> TvTokens.Color.textPrimary
            },
            disabledContainerColor = TvTokens.Color.surfaceInset,
            disabledContentColor = TvTokens.Color.textDisabled,
            focusedContainerColor = TvTokens.Color.accentSoft,
            focusedContentColor = TvTokens.Color.accentStrong,
            pressedContainerColor = TvTokens.Color.surfacePressed,
            pressedContentColor = TvTokens.Color.textPrimary,
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(TvTokens.Focus.outlineWidth, MaterialTheme.colorScheme.primary)),
            pressedBorder = Border.None,
            disabledBorder = Border.None,
            focusedDisabledBorder = Border.None,
        ),
    ) {
        // Reserved marker slot keeps geometry stable when selection toggles:
        // the bronze dot appears in a fixed 8dp slot instead of shifting text.
        Box(
            modifier = Modifier
                .size(TvTokens.Spacing.markerSlot)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
        )
        Spacer(Modifier.width(TvTokens.Spacing.xSmall))
        Text(text, color = Color.Unspecified)
    }
}

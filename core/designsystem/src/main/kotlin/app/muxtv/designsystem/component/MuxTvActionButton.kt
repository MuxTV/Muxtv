package app.muxtv.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens

enum class MuxTvActionStyle {
    /** Solid bronze product CTA, used sparingly for the primary action on a surface. */
    Primary,

    /** Neutral operational/action surface that becomes bronze-accented on focus. */
    Secondary,
}

/**
 * Lounge Light action button with stable geometry and explicit visual hierarchy.
 * Selection uses a reserved marker slot, while focus uses outline + tone so
 * selected and focused states never collapse into the same signal.
 */
@Composable
fun MuxTvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    style: MuxTvActionStyle = MuxTvActionStyle.Secondary,
) {
    val shape = RoundedCornerShape(TvTokens.Shape.buttonCorner)
    val primaryStyle = style == MuxTvActionStyle.Primary

    val restingContainer = when {
        !enabled -> TvTokens.Color.surfaceInset
        primaryStyle -> TvTokens.Color.accent
        selected -> TvTokens.Color.accentSoft2
        else -> TvTokens.Color.surfaceInset
    }
    val restingContent = when {
        !enabled -> TvTokens.Color.textDisabled
        primaryStyle -> TvTokens.Color.onAccent
        selected -> TvTokens.Color.accentStrong
        else -> TvTokens.Color.textPrimary
    }
    val focusedContainer = if (primaryStyle) TvTokens.Color.accentStrong else TvTokens.Color.accentSoft
    val focusedContent = if (primaryStyle) TvTokens.Color.onAccent else TvTokens.Color.accentStrong

    Button(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        enabled = enabled,
        shape = ButtonDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape,
            disabledShape = shape,
            focusedDisabledShape = shape,
        ),
        colors = ButtonDefaults.colors(
            containerColor = restingContainer,
            contentColor = restingContent,
            disabledContainerColor = TvTokens.Color.surfaceInset,
            disabledContentColor = TvTokens.Color.textDisabled,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
            pressedContainerColor = if (primaryStyle) TvTokens.Color.accentStrong else TvTokens.Color.surfacePressed,
            pressedContentColor = if (primaryStyle) TvTokens.Color.onAccent else TvTokens.Color.textPrimary,
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(
                    TvTokens.Focus.outlineWidth,
                    if (primaryStyle) TvTokens.Color.accentStrong else MaterialTheme.colorScheme.primary,
                ),
            ),
            pressedBorder = Border.None,
            disabledBorder = Border.None,
            focusedDisabledBorder = Border.None,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(TvTokens.Spacing.markerSlot)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        if (primaryStyle) TvTokens.Color.onAccent else MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
        )
        Spacer(Modifier.width(TvTokens.Spacing.xSmall))
        Text(text, color = Color.Unspecified)
    }
}

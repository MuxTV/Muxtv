package app.muxtv.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.muxtv.designsystem.TvTokens

/**
 * Thin programme progress track. Green (live/playing/progress only per the
 * Lounge contract). Callers must only render it with valid timing data.
 */
@Composable
fun MuxTvProgrammeProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(TvTokens.Color.liveGreenSoft.copy(alpha = 0.6f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .background(MaterialTheme.colorScheme.secondary),
        )
    }
}

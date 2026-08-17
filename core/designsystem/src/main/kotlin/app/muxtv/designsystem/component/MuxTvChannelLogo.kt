package app.muxtv.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens

/**
 * Deterministic two-letter channel monogram, derived from the
 * display name. Fixed geometry fallback for channels without logos.
 */
fun channelMonogram(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2)
        else -> words.take(2).joinToString(separator = "") { it.take(1) }
    }.uppercase()
}

@Composable
fun MuxTvChannelLogo(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = TvTokens.Size.channelLogo,
    corner: Dp = TvTokens.Shape.logoCorner,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(TvTokens.Color.accentSoft2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = channelMonogram(name),
            style = MaterialTheme.typography.titleLarge,
            color = TvTokens.Color.accentStrong,
            maxLines = 1,
        )
    }
}

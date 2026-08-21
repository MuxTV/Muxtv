package app.muxtv.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Top-right utility clock (Lounge reference). Tabular figures, quiet secondary
 * tone, updates on minute boundaries.
 */
@Composable
fun MuxTvClock(modifier: Modifier = Modifier) {
    var timeText by remember { mutableStateOf(clockText(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            timeText = clockText(now)
            delay(60_000L - (now % 60_000L))
        }
    }
    Text(
        text = timeText,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

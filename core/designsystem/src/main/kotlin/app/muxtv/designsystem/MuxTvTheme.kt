package app.muxtv.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val MuxTvDarkColors = darkColorScheme(
    primary = Color(0xFF89B4FF),
    onPrimary = Color(0xFF071426),
    secondary = Color(0xFF8DE8D0),
    background = Color(0xFF080B12),
    onBackground = Color(0xFFF1F4FA),
    surface = Color(0xFF121824),
    onSurface = Color(0xFFF1F4FA),
    surfaceVariant = Color(0xFF1B2433),
    onSurfaceVariant = Color(0xFFC6CFDC),
)

@Composable
fun MuxTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MuxTvDarkColors, content = content)
}

package app.muxtv.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.lightColorScheme

/**
 * MuxTV Lounge Light semantic palette (design contract #93, docs/design/2026-08-04-muxtv-lounge-light-spec.md).
 *
 * Roles: warm canvas, light rail panels, matte surfaces, bronze focus/selection,
 * green only for live/playing/progress. Error slots stay semantic M3 defaults.
 */
private val LoungeLightColors = lightColorScheme(
    primary = Color(0xFF9B6A32),
    onPrimary = Color(0xFFFFF9F1),
    primaryContainer = Color(0xFFEADDCB),
    onPrimaryContainer = Color(0xFF7F5428),
    secondary = Color(0xFF2F7D3E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFEEE1),
    onSecondaryContainer = Color(0xFF1C4B26),
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF181A1F),
    surface = Color(0xFFF7F7F5),
    onSurface = Color(0xFF181A1F),
    surfaceVariant = Color(0xFFE9EBEE),
    onSurfaceVariant = Color(0xFF5D626A),
    surfaceTint = Color(0xFF9B6A32),
    border = Color(0xFFD6D9DE),
    borderVariant = Color(0xFFE4E7EA),
    scrim = Color(0x66000000),
)

@Composable
fun MuxTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LoungeLightColors, content = content)
}

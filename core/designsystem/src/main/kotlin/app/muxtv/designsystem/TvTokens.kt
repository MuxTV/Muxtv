package app.muxtv.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TvTokens {
    object Color {
        val canvas: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFF1F2F4)
        val canvasMuted: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFE9EBEE)
        val surface: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFF7F7F5)
        val surfaceRaised: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFFAFAF8)
        val surfaceInset: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFECEEF1)
        val surfacePressed: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFE2E4E8)
        val divider: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFD6D9DE)
        val dividerStrong: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFC3C7CD)
        val accent: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF9B6A32)
        val accentStrong: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF7F5428)
        val accentSoft: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFEADDCB)
        val accentSoft2: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFF1E8DC)
        val onAccent: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFFFF9F1)
        val liveGreen: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF2F7D3E)
        val liveGreenSoft: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFDFEEE1)
        val textPrimary: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF181A1F)
        val textSecondary: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF5D626A)
        val textTertiary: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF858B94)
        val textDisabled: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFA8ADB5)
    }
    object Focus {
        const val scale: Float = 1f
        const val cardScale: Float = 1.03f
        val outlineWidth: Dp = 3.dp
        const val focusedAlpha: Float = 1f
        const val unfocusedAlpha: Float = 1f
    }
    object Motion {
        const val focusDurationMillis: Int = 0
        const val screenDurationMillis: Int = 240
        const val overlayInMillis: Int = 200
        const val overlayOutMillis: Int = 140

        /** Strong ease-out for entering state (overlays, reveals). */
        val easeOut: CubicBezierEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

        /** Strong ease-in-out for on-screen morphs (rail expand). */
        val easeInOut: CubicBezierEasing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    }
    object Spacing {
        val micro: Dp = 4.dp
        val markerSlot: Dp = 8.dp
        val xSmall: Dp = 8.dp
        val small: Dp = 12.dp
        val medium: Dp = 20.dp
        val large: Dp = 32.dp
        val xLarge: Dp = 48.dp
        val screenInset: Dp = 56.dp
        val railGutter: Dp = 28.dp
        val sectionGap: Dp = 40.dp
    }
    object Size {
        val railCollapsed: Dp = 88.dp
        val railExpanded: Dp = 248.dp
        val channelRowHeight: Dp = 96.dp
        val channelLogo: Dp = 56.dp
        val homeCardWidth: Dp = 300.dp
        val homeCardHeight: Dp = 140.dp
    }
    object Shape {
        val cardCorner: Dp = 18.dp
        val buttonCorner: Dp = 14.dp
        val heroCorner: Dp = 28.dp
        val largeCardCorner: Dp = 22.dp
        val rowCorner: Dp = 16.dp
        val detailsCorner: Dp = 24.dp
        val logoCorner: Dp = 14.dp
    }
    object Typography {
        val screenTitle: TextUnit = 36.sp
        val sectionTitle: TextUnit = 26.sp
        val cardTitle: TextUnit = 20.sp
        val body: TextUnit = 18.sp
        val metadata: TextUnit = 15.sp
    }
}

package app.muxtv.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object TvTokens {
    object Focus {
        /**
         * Dense TV navigation must not change component geometry on focus.
         * Sparse hero/poster treatments may opt into their own reserved-envelope scale later.
         */
        const val scale: Float = 1f
        val outlineWidth: Dp = 3.dp
        const val focusedAlpha: Float = 1f
        const val unfocusedAlpha: Float = 1f
    }

    object Motion {
        /** Repeated D-pad focus transitions are intentionally immediate. */
        const val focusDurationMillis: Int = 0
        const val screenDurationMillis: Int = 240
    }

    object Spacing {
        val xSmall: Dp = 8.dp
        val small: Dp = 12.dp
        val medium: Dp = 20.dp
        val large: Dp = 32.dp
        val xLarge: Dp = 48.dp
    }

    object Shape {
        val cardCorner: Dp = 18.dp
        val buttonCorner: Dp = 14.dp
    }
}

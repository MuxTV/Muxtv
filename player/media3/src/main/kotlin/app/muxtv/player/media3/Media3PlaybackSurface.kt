package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.muxtv.player.PlaybackControlSession

/**
 * Media3-owned video renderer for a stable playback session.
 *
 * Feature modules receive this as a composition-root callback and therefore never import
 * MediaController, Media3 Compose UI or implementation-specific surface constants.
 */
@AndroidXOptIn(UnstableApi::class)
@Composable
fun Media3PlaybackSurface(
    session: PlaybackControlSession,
    modifier: Modifier = Modifier,
) {
    val media3Session = requireNotNull(session as? Media3PlaybackControlSession) {
        "Media3PlaybackSurface requires a Media3 playback session."
    }
    PlayerSurface(
        player = media3Session.controller,
        modifier = modifier,
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
    )
}

package app.muxtv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.player.media3.Media3PlaybackSessionGateway
import app.muxtv.player.media3.Media3PlaybackSurface
import app.muxtv.player.media3.MuxTvMediaControllerConnector

/**
 * App instrumentation composition fixture that binds the real Media3 adapter to the stable
 * feature ports. Feature production code remains engine-neutral; only the app test composition
 * knows which playback implementation is used.
 */
@Composable
fun PlayerRoute(
    playbackCatalog: PlaybackCatalog,
    controllerConnector: MuxTvMediaControllerConnector,
    profileId: String,
    channelId: String,
    onBack: () -> Unit,
    onOpenDoctor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    playbackStartGateway: PlaybackStartGateway? = null,
    favoriteAction: PlayerFavoriteAction? = null,
) {
    val playbackSessionGateway = remember(controllerConnector) {
        Media3PlaybackSessionGateway(controllerConnector)
    }
    PlayerRoute(
        playbackCatalog = playbackCatalog,
        playbackSessionGateway = playbackSessionGateway,
        playbackSurface = { session, surfaceModifier ->
            Media3PlaybackSurface(
                session = session,
                modifier = surfaceModifier,
            )
        },
        profileId = profileId,
        channelId = channelId,
        onBack = onBack,
        onOpenDoctor = onOpenDoctor,
        modifier = modifier,
        playbackStartGateway = playbackStartGateway,
        favoriteAction = favoriteAction,
    )
}

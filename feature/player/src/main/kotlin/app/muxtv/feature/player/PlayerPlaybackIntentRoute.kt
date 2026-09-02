package app.muxtv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.PlaybackSessionGateway
import app.muxtv.player.PlaybackStartRequest

internal fun playerPlaybackStartRequest(
    profileId: String,
    intent: PlaybackIntent,
    preferredVariantId: String?,
): PlaybackStartRequest = PlaybackStartRequest(
    profileId = profileId,
    intent = intent,
    preferredVariantId = preferredVariantId,
)

/**
 * Semantic Player entry point used by Guide/archive navigation.
 *
 * The existing channel-only PlayerRoute remains the Live-compatible implementation of UI,
 * permission and approval behavior. This overload injects the provider-neutral intent only at
 * the playback start boundary, so the process-owned playback service remains the sole transport,
 * recovery and seek authority.
 */
@Composable
fun PlayerRoute(
    playbackCatalog: PlaybackCatalog,
    playbackSessionGateway: PlaybackSessionGateway,
    playbackSurface: PlaybackSurfaceRenderer,
    profileId: String,
    playbackIntent: PlaybackIntent,
    onBack: () -> Unit,
    onOpenDoctor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    playbackStartGateway: PlaybackStartGateway? = null,
    favoriteAction: PlayerFavoriteAction? = null,
    requestLocalNetworkPermission: suspend () -> PlayerLocalNetworkPermissionOutcome = {
        PlayerLocalNetworkPermissionOutcome.DENIED
    },
    openLocalNetworkPermissionSettings: suspend () -> Boolean = { false },
) {
    val semanticStartGateway = remember(playbackStartGateway, playbackIntent) {
        PlaybackStartGateway { session, request, timeoutMillis ->
            val semanticRequest = playerPlaybackStartRequest(
                profileId = request.profileId,
                intent = playbackIntent,
                preferredVariantId = request.preferredVariantId,
            )
            playbackStartGateway?.start(
                session = session,
                request = semanticRequest,
                timeoutMillis = timeoutMillis,
            ) ?: session.start(
                request = semanticRequest,
                timeoutMillis = timeoutMillis,
            )
        }
    }

    PlayerRoute(
        playbackCatalog = playbackCatalog,
        playbackSessionGateway = playbackSessionGateway,
        playbackSurface = playbackSurface,
        profileId = profileId,
        channelId = playbackIntent.channelId,
        onBack = onBack,
        onOpenDoctor = onOpenDoctor,
        modifier = modifier,
        playbackStartGateway = semanticStartGateway,
        favoriteAction = favoriteAction,
        requestLocalNetworkPermission = requestLocalNetworkPermission,
        openLocalNetworkPermissionSettings = openLocalNetworkPermissionSettings,
    )
}

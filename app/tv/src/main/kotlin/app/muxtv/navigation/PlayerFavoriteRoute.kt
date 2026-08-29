package app.muxtv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.feature.player.PlaybackStartGateway
import app.muxtv.feature.player.PlaybackSurfaceRenderer
import app.muxtv.feature.player.PlayerFavoriteAction
import app.muxtv.feature.player.PlayerRoute
import app.muxtv.player.PlaybackSessionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlayerFavoriteRoute(
    playbackCatalog: PlaybackCatalog,
    channelPreferencesRepository: ChannelPreferencesRepository,
    playbackSessionGateway: PlaybackSessionGateway,
    playbackSurface: PlaybackSurfaceRenderer,
    profileId: String,
    channelId: String,
    onBack: () -> Unit,
    onOpenDoctor: () -> Unit,
    playbackStartGateway: PlaybackStartGateway? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var favoriteOverride by remember(profileId, channelId) { mutableStateOf<Boolean?>(null) }
    var mutationInProgress by remember(profileId, channelId) { mutableStateOf(false) }
    var mutationFailed by remember(profileId, channelId) { mutableStateOf(false) }
    val catalogFavorite by produceState<Boolean?>(
        initialValue = null,
        playbackCatalog,
        profileId,
        channelId,
    ) {
        value = try {
            playbackCatalog.getChannel(profileId = profileId, channelId = channelId)
                ?.summary
                ?.isFavorite
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val isFavorite = favoriteOverride ?: catalogFavorite

    PlayerRoute(
        playbackCatalog = playbackCatalog,
        playbackSessionGateway = playbackSessionGateway,
        playbackSurface = playbackSurface,
        profileId = profileId,
        channelId = channelId,
        onBack = onBack,
        onOpenDoctor = onOpenDoctor,
        playbackStartGateway = playbackStartGateway,
        favoriteAction = isFavorite?.let { favorite ->
            PlayerFavoriteAction(
                label = when {
                    mutationInProgress -> "Сохранение…"
                    favorite -> "★ В избранном"
                    else -> "☆ В избранное"
                },
                enabled = !mutationInProgress,
                onClick = {
                    if (!mutationInProgress) {
                        val requestedFavorite = !favorite
                        mutationInProgress = true
                        mutationFailed = false
                        scope.launch {
                            try {
                                when (
                                    channelPreferencesRepository.setFavorite(
                                        profileId = profileId,
                                        channelId = channelId,
                                        isFavorite = requestedFavorite,
                                    )
                                ) {
                                    ChannelFavoriteMutationResult.Applied,
                                    ChannelFavoriteMutationResult.Unchanged,
                                    -> favoriteOverride = requestedFavorite

                                    ChannelFavoriteMutationResult.NotFound -> mutationFailed = true
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                mutationFailed = true
                            } finally {
                                mutationInProgress = false
                            }
                        }
                    }
                },
                failureLabel = if (mutationFailed) "Не удалось изменить избранное" else null,
            )
        },
        modifier = modifier,
    )
}

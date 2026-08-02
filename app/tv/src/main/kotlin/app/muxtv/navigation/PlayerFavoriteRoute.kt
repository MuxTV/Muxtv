package app.muxtv.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.player.PlayerRoute
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlayerFavoriteRoute(
    playbackCatalog: PlaybackCatalog,
    controllerConnector: MuxTvMediaControllerConnector,
    profileId: String,
    channelId: String,
    onBack: () -> Unit,
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

    Box(modifier = modifier.fillMaxSize()) {
        PlayerRoute(
            playbackCatalog = playbackCatalog,
            controllerConnector = controllerConnector,
            profileId = profileId,
            channelId = channelId,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
        )

        if (isFavorite != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
            ) {
                MuxTvActionButton(
                    text = when {
                        mutationInProgress -> "Сохранение…"
                        isFavorite -> "★ В избранном"
                        else -> "☆ В избранное"
                    },
                    onClick = {
                        if (!mutationInProgress) {
                            val requestedFavorite = !isFavorite
                            mutationInProgress = true
                            mutationFailed = false
                            scope.launch {
                                try {
                                    when (
                                        playbackCatalog.setFavorite(
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
                    enabled = !mutationInProgress,
                    modifier = Modifier.testTag(PLAYER_FAVORITE_TEST_TAG),
                )
                if (mutationFailed) {
                    Text(
                        text = "Не удалось изменить избранное",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

internal const val PLAYER_FAVORITE_TEST_TAG = "player-favorite"

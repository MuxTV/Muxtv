package app.muxtv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.PlaybackSessionRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PlayerRouteState {
    data object Connecting : PlayerRouteState
    data object Resolving : PlayerRouteState

    data class Ready(
        val controller: MediaController,
        val title: String,
    ) : PlayerRouteState

    data class Failed(
        val message: String,
    ) : PlayerRouteState
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerRoute(
    playbackCatalog: PlaybackCatalog,
    controllerConnector: MuxTvMediaControllerConnector,
    profileId: String,
    channelId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routeState by produceState<PlayerRouteState>(
        initialValue = PlayerRouteState.Connecting,
        playbackCatalog,
        controllerConnector,
        profileId,
        channelId,
    ) {
        val controller = runCatching {
            withContext(Dispatchers.IO) {
                controllerConnector.connect().get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }.getOrElse {
            value = PlayerRouteState.Failed("Не удалось подключиться к службе воспроизведения.")
            return@produceState
        }

        value = PlayerRouteState.Resolving
        val channel = runCatching {
            playbackCatalog.getChannel(profileId = profileId, channelId = channelId)
        }.getOrNull()
        val request = runCatching {
            playbackCatalog.resolveVariant(profileId = profileId, channelId = channelId)
        }.getOrNull()
        if (channel == null || request == null) {
            value = PlayerRouteState.Failed("Канал больше не доступен в активном каталоге.")
            return@produceState
        }

        val sessionRequest = runCatching {
            PlaybackSessionRequest(
                mediaId = request.channelId,
                variantId = request.variantId,
                locator = request.locator,
                displayName = channel.summary.displayName,
                artworkUri = channel.summary.logoUrl,
                requestHeaders = request.requestHeaders,
            )
        }.getOrElse {
            value = PlayerRouteState.Failed("Данные выбранного потока недействительны.")
            return@produceState
        }
        val setupResult = runCatching {
            withContext(Dispatchers.IO) {
                controllerConnector
                    .sendPlaybackRequest(controller, sessionRequest)
                    .get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }.getOrNull()
        if (setupResult?.resultCode != SessionResult.RESULT_SUCCESS) {
            value = PlayerRouteState.Failed("Не удалось подготовить выбранный поток.")
            return@produceState
        }

        value = PlayerRouteState.Ready(
            controller = controller,
            title = channel.summary.displayName,
        )
    }

    when (val current = routeState) {
        PlayerRouteState.Connecting -> PlayerMessage(
            title = "Подключение к проигрывателю…",
            onBack = onBack,
            modifier = modifier,
        )

        PlayerRouteState.Resolving -> PlayerMessage(
            title = "Подготовка канала…",
            onBack = onBack,
            modifier = modifier,
        )

        is PlayerRouteState.Failed -> PlayerMessage(
            title = current.message,
            onBack = onBack,
            modifier = modifier,
        )

        is PlayerRouteState.Ready -> PlayerContent(
            controller = current.controller,
            title = current.title,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerContent(
    controller: MediaController,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var isPlaying by remember(controller) { mutableStateOf(controller.isPlaying) }
    var playbackState by remember(controller) { mutableIntStateOf(controller.playbackState) }
    var hasError by remember(controller) { mutableStateOf(controller.playerError != null) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                playbackState = player.playbackState
                hasError = player.playerError != null
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PlayerSurface(
            player = controller,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = playbackStatus(playbackState = playbackState, hasError = hasError),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                MuxTvActionButton(
                    text = if (isPlaying) "Пауза" else "Продолжить",
                    onClick = {
                        if (isPlaying) controller.pause() else controller.play()
                    },
                )
                MuxTvActionButton(
                    text = "Остановить",
                    onClick = controller::stop,
                )
                MuxTvActionButton(
                    text = "Назад к каналам",
                    onClick = onBack,
                )
            }
        }
    }
}

@Composable
private fun PlayerMessage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        MuxTvActionButton(text = "Назад к каналам", onClick = onBack)
    }
}

private fun playbackStatus(
    playbackState: Int,
    hasError: Boolean,
): String = when {
    hasError -> "Ошибка воспроизведения"
    playbackState == Player.STATE_BUFFERING -> "Буферизация"
    playbackState == Player.STATE_READY -> "Готово"
    playbackState == Player.STATE_ENDED -> "Поток завершён"
    else -> "Подготовка"
}

private const val CONTROLLER_TIMEOUT_SECONDS = 20L
private const val COMMAND_TIMEOUT_SECONDS = 10L

package app.muxtv.feature.player

import androidx.annotation.OptIn as AndroidXOptIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
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
import java.util.concurrent.Future
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

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

@AndroidXOptIn(UnstableApi::class)
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
        val controller = try {
            awaitFuture(
                future = controllerConnector.connect(),
                timeoutMillis = CONTROLLER_TIMEOUT_MILLIS,
            )
        } catch (_: TimeoutCancellationException) {
            value = PlayerRouteState.Failed("Служба воспроизведения не ответила вовремя.")
            return@produceState
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = PlayerRouteState.Failed("Не удалось подключиться к службе воспроизведения.")
            return@produceState
        }

        value = PlayerRouteState.Resolving
        val channel = try {
            playbackCatalog.getChannel(profileId = profileId, channelId = channelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val request = try {
            playbackCatalog.resolveVariant(profileId = profileId, channelId = channelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (channel == null || request == null) {
            value = PlayerRouteState.Failed("Канал больше не доступен в активном каталоге.")
            return@produceState
        }

        val sessionRequest = try {
            PlaybackSessionRequest(
                mediaId = request.channelId,
                variantId = request.variantId,
                locator = request.locator,
                displayName = channel.summary.displayName,
                artworkUri = channel.summary.logoUrl,
                requestHeaders = request.requestHeaders,
            )
        } catch (_: IllegalArgumentException) {
            value = PlayerRouteState.Failed("Данные выбранного потока недействительны.")
            return@produceState
        }
        val setupResult = try {
            val setupFuture = controllerConnector.sendPlaybackRequest(controller, sessionRequest)
            awaitFuture(
                future = setupFuture,
                timeoutMillis = COMMAND_TIMEOUT_MILLIS,
            )
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
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

@AndroidXOptIn(UnstableApi::class)
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
    val primaryActionFocusRequester = remember(controller) { FocusRequester() }

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
    LaunchedEffect(controller) {
        withFrameNanos { }
        primaryActionFocusRequester.requestFocus()
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
                    modifier = Modifier
                        .testTag(PLAYER_PRIMARY_ACTION_TEST_TAG)
                        .focusRequester(primaryActionFocusRequester),
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
    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        backFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        MuxTvActionButton(
            text = "Назад к каналам",
            onClick = onBack,
            modifier = Modifier
                .testTag(PLAYER_BACK_TEST_TAG)
                .focusRequester(backFocusRequester),
        )
    }
}

private suspend fun <T> awaitFuture(
    future: Future<T>,
    timeoutMillis: Long,
): T = withTimeout(timeoutMillis) {
    runInterruptible(Dispatchers.IO) { future.get() }
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

private const val PLAYER_PRIMARY_ACTION_TEST_TAG = "player-primary-action"
private const val PLAYER_BACK_TEST_TAG = "player-back"
private const val CONTROLLER_TIMEOUT_MILLIS = 20_000L
private const val COMMAND_TIMEOUT_MILLIS = 10_000L

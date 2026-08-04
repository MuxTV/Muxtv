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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.media3.MediaControllerOperationException
import app.muxtv.player.media3.MediaControllerOperationFailure
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.PlaybackSessionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private sealed interface PlayerRouteState {
    data object Connecting : PlayerRouteState
    data object Resolving : PlayerRouteState

    data class HttpApprovalRequired(
        val displayOrigin: String,
        val variantId: String,
    ) : PlayerRouteState

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
    val connectionEpoch by controllerConnector.connectionEpoch.collectAsState()
    val approvalScope = rememberCoroutineScope()
    var approvalGeneration by remember(profileId, channelId) { mutableIntStateOf(0) }
    var approvalInProgress by remember(profileId, channelId) { mutableStateOf(false) }
    var approvalFailure by remember(profileId, channelId) { mutableStateOf<String?>(null) }
    val routeState by produceState<PlayerRouteState>(
        initialValue = PlayerRouteState.Connecting,
        playbackCatalog,
        controllerConnector,
        connectionEpoch,
        approvalGeneration,
        profileId,
        channelId,
    ) {
        value = PlayerRouteState.Connecting
        val controller = try {
            controllerConnector.awaitController(CONTROLLER_TIMEOUT_MILLIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MediaControllerOperationException) {
            value = PlayerRouteState.Failed(connectionFailureMessage(error.failure))
            return@produceState
        } catch (_: Exception) {
            value = PlayerRouteState.Failed(CONNECTION_FAILED_MESSAGE)
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
        val resolution = try {
            playbackCatalog.resolveVariant(profileId = profileId, channelId = channelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (channel == null || resolution == null) {
            value = PlayerRouteState.Failed("Канал больше не доступен в активном каталоге.")
            return@produceState
        }

        when (resolution) {
            is PlaybackVariantResolution.InsecureTransportApprovalRequired -> {
                value = PlayerRouteState.HttpApprovalRequired(
                    displayOrigin = resolution.displayOrigin,
                    variantId = resolution.variantId,
                )
                return@produceState
            }

            is PlaybackVariantResolution.AccessUnavailable -> {
                value = PlayerRouteState.Failed(accessFailureMessage(resolution.reason))
                return@produceState
            }

            is PlaybackVariantResolution.Ready -> {
                val request = resolution.request
                val sessionRequest = try {
                    PlaybackSessionRequest(
                        profileId = profileId,
                        mediaId = request.channelId,
                        variantId = request.variantId,
                        locator = request.locator,
                        displayName = channel.summary.displayName,
                        artworkUri = channel.summary.logoUrl,
                        requestHeaders = request.requestHeaders,
                        insecureHttpApproved = request.insecureHttpApproved,
                    )
                } catch (_: IllegalArgumentException) {
                    value = PlayerRouteState.Failed("Данные выбранного потока недействительны.")
                    return@produceState
                }

                currentCoroutineContext().ensureActive()
                val setupResult = try {
                    controllerConnector.awaitPlaybackRequest(
                        controller = controller,
                        request = sessionRequest,
                        timeoutMillis = COMMAND_TIMEOUT_MILLIS,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: MediaControllerOperationException) {
                    value = PlayerRouteState.Failed(commandFailureMessage(error.failure))
                    return@produceState
                } catch (_: Exception) {
                    value = PlayerRouteState.Failed(COMMAND_FAILED_MESSAGE)
                    return@produceState
                }
                if (setupResult.resultCode != SessionResult.RESULT_SUCCESS) {
                    value = PlayerRouteState.Failed(COMMAND_FAILED_MESSAGE)
                    return@produceState
                }

                currentCoroutineContext().ensureActive()
                value = PlayerRouteState.Ready(
                    controller = controller,
                    title = channel.summary.displayName,
                )
            }
        }
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

        is PlayerRouteState.HttpApprovalRequired -> {
            LaunchedEffect(current.displayOrigin) {
                approvalFailure = null
            }
            HttpApprovalMessage(
                displayOrigin = current.displayOrigin,
                failure = approvalFailure,
                approving = approvalInProgress,
                onApprove = {
                    if (!approvalInProgress) {
                        approvalInProgress = true
                        approvalFailure = null
                        approvalScope.launch {
                            try {
                                when (
                                    playbackCatalog.approveInsecurePlayback(
                                        profileId = profileId,
                                        channelId = channelId,
                                        variantId = current.variantId,
                                    )
                                ) {
                                    PlaybackAccessMutationResult.Applied,
                                    PlaybackAccessMutationResult.Unchanged,
                                    -> approvalGeneration += 1

                                    PlaybackAccessMutationResult.CapacityExceeded ->
                                        approvalFailure =
                                            "Достигнут лимит HTTP-разрешений. Сбросьте их в источниках."

                                    PlaybackAccessMutationResult.NotFound,
                                    PlaybackAccessMutationResult.Corrupted,
                                    PlaybackAccessMutationResult.Unavailable,
                                    PlaybackAccessMutationResult.InvalidLocator,
                                    -> approvalFailure = HTTP_APPROVAL_FAILED_MESSAGE
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                approvalFailure = HTTP_APPROVAL_FAILED_MESSAGE
                            } finally {
                                approvalInProgress = false
                            }
                        }
                    }
                },
                onBack = onBack,
                modifier = modifier,
            )
        }

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

@Composable
private fun HttpApprovalMessage(
    displayOrigin: String,
    failure: String?,
    approving: Boolean,
    onApprove: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val approveFocusRequester = remember(displayOrigin) { FocusRequester() }
    LaunchedEffect(displayOrigin) {
        withFrameNanos { }
        approveFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(
            text = "Этот поток использует незащищённое HTTP-соединение.",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Разрешить воспроизведение только для $displayOrigin?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        failure?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = if (approving) "Сохранение…" else "Разрешить для этого адреса",
                onClick = onApprove,
                enabled = !approving,
                modifier = Modifier
                    .testTag(PLAYER_HTTP_APPROVE_TEST_TAG)
                    .focusRequester(approveFocusRequester),
            )
            MuxTvActionButton(
                text = "Назад к каналам",
                onClick = onBack,
                modifier = Modifier.testTag(PLAYER_BACK_TEST_TAG),
            )
        }
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

private fun accessFailureMessage(reason: PlaybackAccessUnavailableReason): String = when (reason) {
    PlaybackAccessUnavailableReason.InvalidLocator ->
        "Данные выбранного потока недействительны."

    PlaybackAccessUnavailableReason.CredentialNotFound ->
        "Доступ к источнику не найден. Добавьте источник заново."

    PlaybackAccessUnavailableReason.CredentialCorrupted,
    PlaybackAccessUnavailableReason.CredentialUnavailable,
    -> "Защищённые данные источника недоступны."
}

private fun connectionFailureMessage(failure: MediaControllerOperationFailure): String = when (failure) {
    MediaControllerOperationFailure.ConnectionTimedOut ->
        "Служба воспроизведения не ответила вовремя."

    MediaControllerOperationFailure.ConnectionCancelled ->
        "Подключение к службе воспроизведения было прервано."

    else -> CONNECTION_FAILED_MESSAGE
}

private fun commandFailureMessage(failure: MediaControllerOperationFailure): String = when (failure) {
    MediaControllerOperationFailure.CommandTimedOut ->
        "Служба воспроизведения не успела подготовить поток."

    MediaControllerOperationFailure.CommandCancelled ->
        "Подготовка потока была прервана."

    else -> COMMAND_FAILED_MESSAGE
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

private const val CONNECTION_FAILED_MESSAGE =
    "Не удалось подключиться к службе воспроизведения."
private const val COMMAND_FAILED_MESSAGE = "Не удалось подготовить выбранный поток."
private const val HTTP_APPROVAL_FAILED_MESSAGE = "Не удалось сохранить HTTP-разрешение."
private const val PLAYER_PRIMARY_ACTION_TEST_TAG = "player-primary-action"
private const val PLAYER_HTTP_APPROVE_TEST_TAG = "player-http-approve"
private const val PLAYER_BACK_TEST_TAG = "player-back"
private const val CONTROLLER_TIMEOUT_MILLIS = 20_000L
private const val COMMAND_TIMEOUT_MILLIS = 10_000L
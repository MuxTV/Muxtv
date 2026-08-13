package app.muxtv.feature.player

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.media3.MediaControllerOperationException
import app.muxtv.player.media3.MediaControllerOperationFailure
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerFavoriteAction(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val failureLabel: String? = null,
) {
    init {
        require(label.isNotBlank())
    }
}

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
        val doctorAvailable: Boolean = false,
    ) : PlayerRouteState
}

fun interface PlaybackStartGateway {
    suspend fun start(
        controller: MediaController,
        request: PlaybackStartRequest,
        timeoutMillis: Long,
    ): PlaybackStartResult
}

@AndroidXOptIn(UnstableApi::class)
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
    val connectionEpoch by controllerConnector.connectionEpoch.collectAsState()
    val startGateway = playbackStartGateway ?: remember(controllerConnector) {
        PlaybackStartGateway { controller, request, timeoutMillis ->
            controllerConnector.awaitPlaybackStart(
                controller = controller,
                request = request,
                timeoutMillis = timeoutMillis,
            )
        }
    }
    val approvalScope = rememberCoroutineScope()
    var approvalGeneration by remember(profileId, channelId) { mutableIntStateOf(0) }
    var approvalInProgress by remember(profileId, channelId) { mutableStateOf(false) }
    var approvalFailure by remember(profileId, channelId) { mutableStateOf<String?>(null) }
    val routeState by produceState<PlayerRouteState>(
        initialValue = PlayerRouteState.Connecting,
        playbackCatalog,
        controllerConnector,
        startGateway,
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
        if (channel == null) {
            value = PlayerRouteState.Failed("Канал больше не доступен в активном каталоге.")
            return@produceState
        }

        currentCoroutineContext().ensureActive()
        val startResult = try {
            startGateway.start(
                controller = controller,
                request = PlaybackStartRequest(profileId, channelId),
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

        when (startResult) {
            is PlaybackStartResult.InsecureHttpApprovalRequired -> {
                value = PlayerRouteState.HttpApprovalRequired(
                    displayOrigin = startResult.displayOrigin,
                    variantId = startResult.variantId,
                )
                return@produceState
            }

            is PlaybackStartResult.Rejected -> {
                value = PlayerRouteState.Failed(
                    message = startFailureMessage(startResult.reason),
                    doctorAvailable = startResult.observationAvailable,
                )
                return@produceState
            }

            PlaybackStartResult.Started -> {
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
            onOpenDoctor = onOpenDoctor.takeIf { current.doctorAvailable },
            modifier = modifier,
        )

        is PlayerRouteState.Ready -> PlayerContent(
            controller = current.controller,
            title = current.title,
            contentIdentity = channelId,
            favoriteAction = favoriteAction,
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
    contentIdentity: Any,
    favoriteAction: PlayerFavoriteAction?,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var isPlaying by remember(controller) { mutableStateOf(controller.isPlaying) }
    var playbackState by remember(controller) { mutableIntStateOf(controller.playbackState) }
    var hasError by remember(controller) { mutableStateOf(controller.playerError != null) }
    var controlsVisible by remember(contentIdentity) { mutableStateOf(false) }
    var lastInteractionNanos by remember(contentIdentity) { mutableLongStateOf(System.nanoTime()) }
    val primaryActionFocusRequester = remember(controller) { FocusRequester() }
    val surfaceFocusRequester = remember { FocusRequester() }

    fun revealControls() {
        lastInteractionNanos = System.nanoTime()
        controlsVisible = true
    }

    fun registerInteraction() {
        lastInteractionNanos = System.nanoTime()
    }

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

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            withFrameNanos { }
            primaryActionFocusRequester.requestFocus()
        } else {
            withFrameNanos { }
            surfaceFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(contentIdentity, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        while (isActive) {
            val elapsedNanos = System.nanoTime() - lastInteractionNanos
            val remainingMillis = (OVERLAY_HIDE_NANOS - elapsedNanos) / 1_000_000L
            if (remainingMillis <= 0L) {
                controlsVisible = false
                return@LaunchedEffect
            }
            delay(remainingMillis.coerceAtMost(1_000L).coerceAtLeast(100L))
        }
    }

    BackHandler(enabled = controlsVisible) {
        registerInteraction()
        controlsVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(PLAYER_SURFACE_TEST_TAG)
            .then(
                if (controlsVisible) {
                    Modifier
                } else {
                    Modifier
                        .focusRequester(surfaceFocusRequester)
                        .clickable(
                            role = Role.Button,
                            onClick = ::revealControls,
                        )
                },
            ),
    ) {
        PlayerSurface(
            player = controller,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        )

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 48.dp, vertical = 24.dp)
                    .testTag(PLAYER_OVERLAY_TEST_TAG)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            registerInteraction()
                        }
                        false
                    },
                verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = playbackStatus(playbackState = playbackState, hasError = hasError),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    favoriteAction?.let { favorite ->
                        MuxTvActionButton(
                            text = favorite.label,
                            onClick = {
                                registerInteraction()
                                favorite.onClick()
                            },
                            enabled = favorite.enabled,
                            modifier = Modifier.testTag(PLAYER_FAVORITE_TEST_TAG),
                        )
                    }
                    MuxTvActionButton(
                        text = if (isPlaying) "Пауза" else "Продолжить",
                        onClick = {
                            registerInteraction()
                            if (isPlaying) controller.pause() else controller.play()
                        },
                        modifier = Modifier
                            .testTag(PLAYER_PRIMARY_ACTION_TEST_TAG)
                            .focusRequester(primaryActionFocusRequester),
                    )
                    MuxTvActionButton(
                        text = "Остановить",
                        onClick = {
                            registerInteraction()
                            controller.stop()
                        },
                    )
                    MuxTvActionButton(
                        text = "Назад к каналам",
                        onClick = {
                            registerInteraction()
                            onBack()
                        },
                    )
                }
                favoriteAction?.failureLabel?.let { failureLabel ->
                    Text(
                        text = failureLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerMessage(
    title: String,
    onBack: () -> Unit,
    onOpenDoctor: (() -> Unit)? = null,
    modifier: Modifier,
) {
    val primaryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        primaryFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            onOpenDoctor?.let { openDoctor ->
                MuxTvActionButton(
                    text = "Диагностика",
                    onClick = openDoctor,
                    modifier = Modifier
                        .testTag(PLAYER_DOCTOR_TEST_TAG)
                        .focusRequester(primaryFocusRequester),
                )
            }
            MuxTvActionButton(
                text = "Назад к каналам",
                onClick = onBack,
                modifier = Modifier
                    .testTag(PLAYER_BACK_TEST_TAG)
                    .then(
                        if (onOpenDoctor == null) {
                            Modifier.focusRequester(primaryFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

private fun startFailureMessage(reason: PlaybackStartFailure): String = when (reason) {
    PlaybackStartFailure.ChannelUnavailable ->
        "Канал больше не доступен в активном каталоге."
    PlaybackStartFailure.AccessUnavailable ->
        "Защищённые данные источника недоступны."
    PlaybackStartFailure.RecoveryExhausted ->
        "Не удалось воспроизвести доступные варианты канала."
    PlaybackStartFailure.CommandFailed -> COMMAND_FAILED_MESSAGE
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
private const val PLAYER_DOCTOR_TEST_TAG = "player-doctor"
private const val PLAYER_SURFACE_TEST_TAG = "player-surface"
private const val PLAYER_OVERLAY_TEST_TAG = "player-overlay"
private const val PLAYER_FAVORITE_TEST_TAG = "player-favorite"
private const val CONTROLLER_TIMEOUT_MILLIS = 20_000L
private const val COMMAND_TIMEOUT_MILLIS = 25_000L
private const val OVERLAY_HIDE_NANOS = 6_000_000_000L

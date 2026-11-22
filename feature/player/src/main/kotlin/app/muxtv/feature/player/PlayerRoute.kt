package app.muxtv.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.PlaybackControlSession
import app.muxtv.player.PlaybackSessionGateway
import app.muxtv.player.PlaybackSessionOperationException
import app.muxtv.player.PlaybackSessionOperationFailure
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

enum class PlayerLocalNetworkPermissionOutcome {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

private sealed interface PlayerRouteState {
    data object Connecting : PlayerRouteState
    data object Resolving : PlayerRouteState

    data class LocalNetworkPermissionRequired(
        val variantId: String,
    ) : PlayerRouteState

    data class HttpApprovalRequired(
        val displayOrigin: String,
        val variantId: String,
    ) : PlayerRouteState

    data class Ready(
        val session: PlaybackControlSession,
        val title: String,
    ) : PlayerRouteState

    data class Failed(
        val message: String,
        val doctorAvailable: Boolean = false,
    ) : PlayerRouteState
}

private data class PlayerLocalNetworkPermissionDenial(
    val variantId: String,
    val permanently: Boolean,
)

/** Test seam for starting playback without exposing an engine-specific controller to presentation. */
fun interface PlaybackStartGateway {
    suspend fun start(
        session: PlaybackControlSession,
        request: PlaybackStartRequest,
        timeoutMillis: Long,
    ): PlaybackStartResult
}

@Composable
fun PlayerRoute(
    playbackCatalog: PlaybackCatalog,
    playbackSessionGateway: PlaybackSessionGateway,
    playbackSurface: PlaybackSurfaceRenderer,
    profileId: String,
    channelId: String,
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
    val connectionEpoch by playbackSessionGateway.connectionEpoch.collectAsState()
    val startGateway = playbackStartGateway ?: remember {
        PlaybackStartGateway { session, request, timeoutMillis ->
            session.start(request = request, timeoutMillis = timeoutMillis)
        }
    }
    val actionScope = rememberCoroutineScope()
    var approvalGeneration by remember(profileId, channelId) { mutableIntStateOf(0) }
    var approvalInProgress by remember(profileId, channelId) { mutableStateOf(false) }
    var approvalFailure by remember(profileId, channelId) { mutableStateOf<String?>(null) }
    var localNetworkGeneration by remember(profileId, channelId) { mutableIntStateOf(0) }
    var localNetworkPreferredVariantId by remember(profileId, channelId) {
        mutableStateOf<String?>(null)
    }
    var localNetworkInProgress by remember(profileId, channelId) { mutableStateOf(false) }
    var localNetworkDenial by remember(profileId, channelId) {
        mutableStateOf<PlayerLocalNetworkPermissionDenial?>(null)
    }
    val routeState by produceState<PlayerRouteState>(
        initialValue = PlayerRouteState.Connecting,
        playbackCatalog,
        playbackSessionGateway,
        startGateway,
        connectionEpoch,
        approvalGeneration,
        localNetworkGeneration,
        localNetworkPreferredVariantId,
        profileId,
        channelId,
    ) {
        value = PlayerRouteState.Connecting
        val session = try {
            playbackSessionGateway.awaitSession(SESSION_TIMEOUT_MILLIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlaybackSessionOperationException) {
            value = PlayerRouteState.Failed(connectionFailureMessage(error.failure))
            return@produceState
        } catch (_: Exception) {
            value = PlayerRouteState.Failed(CONNECTION_FAILED_MESSAGE)
            return@produceState
        }

        try {
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
                    session = session,
                    request = PlaybackStartRequest(
                        profileId = profileId,
                        channelId = channelId,
                        preferredVariantId = localNetworkPreferredVariantId,
                    ),
                    timeoutMillis = COMMAND_TIMEOUT_MILLIS,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PlaybackSessionOperationException) {
                value = PlayerRouteState.Failed(commandFailureMessage(error.failure))
                return@produceState
            } catch (_: Exception) {
                value = PlayerRouteState.Failed(COMMAND_FAILED_MESSAGE)
                return@produceState
            }

            when (startResult) {
                is PlaybackStartResult.LocalNetworkPermissionRequired -> {
                    value = PlayerRouteState.LocalNetworkPermissionRequired(
                        variantId = startResult.variantId,
                    )
                }

                is PlaybackStartResult.InsecureHttpApprovalRequired -> {
                    value = PlayerRouteState.HttpApprovalRequired(
                        displayOrigin = startResult.displayOrigin,
                        variantId = startResult.variantId,
                    )
                }

                is PlaybackStartResult.Rejected -> {
                    value = PlayerRouteState.Failed(
                        message = startFailureMessage(startResult.reason),
                        doctorAvailable = startResult.observationAvailable,
                    )
                }

                PlaybackStartResult.Started -> {
                    currentCoroutineContext().ensureActive()
                    value = PlayerRouteState.Ready(
                        session = session,
                        title = channel.summary.displayName,
                    )
                    // Keep adapter observation resources alive for exactly this route-state
                    // generation. Cancellation on navigation/reconnect closes only the session
                    // adapter, never the process-owned playback service/player.
                    awaitCancellation()
                }
            }
        } finally {
            session.close()
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

        is PlayerRouteState.LocalNetworkPermissionRequired -> {
            LaunchedEffect(current.variantId) {
                if (localNetworkDenial?.variantId != current.variantId) {
                    localNetworkDenial = null
                }
            }
            val denial = localNetworkDenial?.takeIf { it.variantId == current.variantId }
            LocalNetworkPermissionMessage(
                permanentlyDenied = denial?.permanently == true,
                temporarilyDenied = denial != null && !denial.permanently,
                requesting = localNetworkInProgress,
                onRequestPermission = {
                    if (!localNetworkInProgress) {
                        localNetworkInProgress = true
                        actionScope.launch {
                            try {
                                when (requestLocalNetworkPermission()) {
                                    PlayerLocalNetworkPermissionOutcome.GRANTED -> {
                                        localNetworkDenial = null
                                        localNetworkPreferredVariantId = current.variantId
                                        localNetworkGeneration += 1
                                    }

                                    PlayerLocalNetworkPermissionOutcome.DENIED -> {
                                        localNetworkDenial = PlayerLocalNetworkPermissionDenial(
                                            variantId = current.variantId,
                                            permanently = false,
                                        )
                                    }

                                    PlayerLocalNetworkPermissionOutcome.PERMANENTLY_DENIED -> {
                                        localNetworkDenial = PlayerLocalNetworkPermissionDenial(
                                            variantId = current.variantId,
                                            permanently = true,
                                        )
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                localNetworkDenial = PlayerLocalNetworkPermissionDenial(
                                    variantId = current.variantId,
                                    permanently = false,
                                )
                            } finally {
                                localNetworkInProgress = false
                            }
                        }
                    }
                },
                onOpenSettings = {
                    if (!localNetworkInProgress) {
                        localNetworkInProgress = true
                        actionScope.launch {
                            try {
                                if (openLocalNetworkPermissionSettings()) {
                                    localNetworkDenial = null
                                    localNetworkPreferredVariantId = current.variantId
                                    localNetworkGeneration += 1
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } finally {
                                localNetworkInProgress = false
                            }
                        }
                    }
                },
                onBack = onBack,
                modifier = modifier,
            )
        }

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
                        actionScope.launch {
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

        is PlayerRouteState.Ready -> PlayerSurfaceContent(
            session = current.session,
            playbackSurface = playbackSurface,
            title = current.title,
            favoriteSupported = favoriteAction != null,
            contentIdentity = channelId,
            favoriteAction = favoriteAction,
            stopAction = PlayerSurfaceAction(
                label = "Остановить",
                onClick = { current.session.stop() },
            ),
            backAction = PlayerSurfaceAction(
                label = "Назад к каналам",
                onClick = onBack,
            ),
            modifier = modifier,
        )
    }
}

@Composable
private fun LocalNetworkPermissionMessage(
    permanentlyDenied: Boolean,
    temporarilyDenied: Boolean,
    requesting: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val actionFocusRequester = remember(permanentlyDenied, temporarilyDenied) { FocusRequester() }
    LaunchedEffect(permanentlyDenied, temporarilyDenied) {
        withFrameNanos { }
        actionFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(
            text = when {
                permanentlyDenied ->
                    "Доступ к локальной сети отключён для MuxTV. Разрешите его в настройках Android."
                temporarilyDenied -> "Доступ к локальной сети не предоставлен."
                else -> "Для этого локального потока требуется доступ к локальной сети."
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = when {
                    requesting -> "Проверка…"
                    permanentlyDenied -> "Открыть настройки"
                    temporarilyDenied -> "Повторить"
                    else -> "Разрешить доступ"
                },
                onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission,
                enabled = !requesting,
                modifier = Modifier
                    .testTag(PLAYER_LOCAL_NETWORK_ACTION_TEST_TAG)
                    .focusRequester(actionFocusRequester),
            )
            MuxTvActionButton(
                text = "Назад к каналам",
                onClick = onBack,
                modifier = Modifier.testTag(PLAYER_BACK_TEST_TAG),
            )
        }
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

private fun connectionFailureMessage(failure: PlaybackSessionOperationFailure): String = when (failure) {
    PlaybackSessionOperationFailure.ConnectionTimedOut ->
        "Служба воспроизведения не ответила вовремя."

    PlaybackSessionOperationFailure.ConnectionCancelled ->
        "Подключение к службе воспроизведения было прервано."

    else -> CONNECTION_FAILED_MESSAGE
}

private fun commandFailureMessage(failure: PlaybackSessionOperationFailure): String = when (failure) {
    PlaybackSessionOperationFailure.CommandTimedOut ->
        "Служба воспроизведения не успела подготовить поток."

    PlaybackSessionOperationFailure.CommandCancelled ->
        "Подготовка потока была прервана."

    else -> COMMAND_FAILED_MESSAGE
}

private const val CONNECTION_FAILED_MESSAGE =
    "Не удалось подключиться к службе воспроизведения."
private const val COMMAND_FAILED_MESSAGE = "Не удалось подготовить выбранный поток."
private const val HTTP_APPROVAL_FAILED_MESSAGE = "Не удалось сохранить HTTP-разрешение."
private const val PLAYER_LOCAL_NETWORK_ACTION_TEST_TAG = "player-local-network-action"
private const val PLAYER_HTTP_APPROVE_TEST_TAG = "player-http-approve"
private const val PLAYER_BACK_TEST_TAG = "player-back"
private const val PLAYER_DOCTOR_TEST_TAG = "player-doctor"
private const val SESSION_TIMEOUT_MILLIS = 20_000L
private const val COMMAND_TIMEOUT_MILLIS = 25_000L

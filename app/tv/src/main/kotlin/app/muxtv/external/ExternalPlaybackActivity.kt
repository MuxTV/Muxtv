package app.muxtv.external

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.ExternalPlaybackDescriptor
import app.muxtv.player.ExternalPlaybackLeaseRegistry
import app.muxtv.player.ExternalPlaybackStartFailure
import app.muxtv.player.ExternalPlaybackStartResult
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import app.muxtv.player.PlaybackObservationRecorder
import app.muxtv.player.media3.MediaControllerOperationException
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.PlaybackSetupId
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ExternalPlaybackActivity : ComponentActivity() {
    @Inject
    lateinit var leaseRegistry: ExternalPlaybackLeaseRegistry

    @Inject
    lateinit var originGrantStore: ExternalPlaybackOriginGrantStore

    @Inject
    lateinit var controllerConnector: MuxTvMediaControllerConnector

    @Inject
    lateinit var observationRecorder: PlaybackObservationRecorder

    private val permissionGate = LocalNetworkPermissionGate(Build.VERSION.SDK_INT)

    private var uiState by mutableStateOf<ExternalUiState>(ExternalUiState.Starting)
    private var accepted: ExternalPlaybackIntentResult.Accepted? = null
    private var sessionId: String? = null
    private var generation = 0
    private var pendingSetupId: PlaybackSetupId? = null
    private var activeController: MediaController? = null
    private var pendingApprovalOrigin: ExternalPlaybackOrigin? = null

    private val lanPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
        ::handleLanPermissionResult,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuxTvTheme {
                ExternalPlaybackScreen(
                    state = uiState,
                    onApproveLan = { requestLanPermission() },
                    onRetryGate = ::retryGates,
                    onApproveHttp = ::approveHttpOrigin,
                    onRetryPlayback = { accepted?.let(::beginSetup) },
                    onStop = ::stopAndFinish,
                    onBack = { finish() },
                )
            }
        }
        acceptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        replaceSession()
        acceptIntent(intent)
    }

    override fun onDestroy() {
        replaceSession()
        super.onDestroy()
    }

    private fun replaceSession() {
        generation += 1
        val controller = activeController
        val setupId = pendingSetupId
        if (controller != null && setupId != null) {
            controllerConnector.cancelSetup(controller, setupId)
        }
        sessionId?.let(leaseRegistry::removeSession)
        sessionId = null
        pendingSetupId = null
        activeController = null
        accepted = null
        pendingApprovalOrigin = null
    }

    private fun acceptIntent(intent: Intent) {
        val parsed = ExternalPlaybackIntentParser.parse(
            action = intent.action,
            uriString = intent.dataString,
            mimeType = intent.type,
            displayTitle = intent.getStringExtra(Intent.EXTRA_TITLE)
                ?: intent.getStringExtra(EXTRA_TITLE_LITERAL),
            sourcePackage = intent.getPackage(),
        )
        when (parsed) {
            is ExternalPlaybackIntentResult.Rejected -> {
                recordObservation(PlaybackObservationKind.EXTERNAL_INTENT_REJECTED)
                uiState = ExternalUiState.IntentRejected(parsed.reason)
            }

            is ExternalPlaybackIntentResult.Accepted -> {
                recordObservation(PlaybackObservationKind.EXTERNAL_INTENT_ACCEPTED)
                accepted = parsed
                evaluateGates(parsed)
            }
        }
    }

    private fun evaluateGates(parsed: ExternalPlaybackIntentResult.Accepted) {
        val origin = ExternalPlaybackOrigin.fromLocator(parsed.locator)
        if (origin == null) {
            uiState = ExternalUiState.Failed(INVALID_LOCATOR_MESSAGE)
            return
        }
        val classification = LocalNetworkTargetClassifier.classify(origin.host)
        if (permissionGate.permissionRequired(classification) &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            uiState = ExternalUiState.LanRationale(origin.host)
            return
        }
        continueGatesAfterPermission()
    }

    private fun requestLanPermission() {
        lanPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun handleLanPermissionResult(granted: Boolean) {
        if (granted) {
            continueGatesAfterPermission()
            return
        }
        val rationaleAvailable = shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        uiState = ExternalUiState.LanDenied(
            permanentlyDenied = !rationaleAvailable,
        )
    }

    private fun retryGates() {
        val parsed = accepted ?: return
        uiState = ExternalUiState.Starting
        evaluateGates(parsed)
    }

    private fun continueGatesAfterPermission() {
        val parsed = accepted ?: return
        val origin = ExternalPlaybackOrigin.fromLocator(parsed.locator) ?: return
        if (origin.isCleartext && !originGrantStore.contains(origin)) {
            pendingApprovalOrigin = origin
            uiState = ExternalUiState.HttpApprovalRequired(origin.encoded)
            return
        }
        beginSetup(parsed)
    }

    private fun approveHttpOrigin() {
        val origin = pendingApprovalOrigin ?: return
        val parsed = accepted ?: return
        when (originGrantStore.approve(origin)) {
            ExternalPlaybackOriginGrantResult.Applied,
            ExternalPlaybackOriginGrantResult.Unchanged,
            -> beginSetup(parsed)

            ExternalPlaybackOriginGrantResult.CapacityExceeded ->
                uiState = ExternalUiState.Failed(APPROVAL_CAPACITY_MESSAGE)
        }
    }

    private fun beginSetup(parsed: ExternalPlaybackIntentResult.Accepted) {
        val myGeneration = ++generation
        val leaseSessionId = UUID.randomUUID().toString()
        sessionId = leaseSessionId
        uiState = ExternalUiState.Preparing
        lifecycleScope.launch {
            try {
                val controller = controllerConnector.awaitController(CONTROLLER_TIMEOUT_MILLIS)
                if (myGeneration != generation) return@launch
                val origin = ExternalPlaybackOrigin.fromLocator(parsed.locator)
                val descriptor = ExternalPlaybackDescriptor(
                    locator = parsed.locator,
                    mimeType = parsed.mimeType,
                    displayTitle = parsed.displayTitle,
                    sourcePackage = parsed.sourcePackage,
                    cleartextApproved = origin == null || !origin.isCleartext ||
                        originGrantStore.contains(origin),
                )
                val leaseId = leaseRegistry.register(
                    descriptor = descriptor,
                    sessionId = leaseSessionId,
                    nowEpochMillis = System.currentTimeMillis(),
                )
                if (myGeneration != generation) {
                    leaseRegistry.removeSession(leaseSessionId)
                    return@launch
                }
                val setupId = PlaybackSetupId.create()
                pendingSetupId = setupId
                activeController = controller
                val result = controllerConnector.awaitExternalPlaybackStart(
                    controller = controller,
                    leaseId = leaseId,
                    setupId = setupId,
                    timeoutMillis = COMMAND_TIMEOUT_MILLIS,
                )
                if (myGeneration != generation) return@launch
                when (result) {
                    ExternalPlaybackStartResult.Started -> uiState = ExternalUiState.Playing(
                        controller = controller,
                        title = parsed.displayTitle ?: DEFAULT_TITLE,
                    )

                    is ExternalPlaybackStartResult.Rejected ->
                        uiState = ExternalUiState.Failed(
                            externalFailureMessage(result.reason),
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MediaControllerOperationException) {
                if (myGeneration == generation) {
                    uiState = ExternalUiState.Failed(CONNECTION_FAILED_MESSAGE)
                }
            } catch (_: Exception) {
                if (myGeneration == generation) {
                    uiState = ExternalUiState.Failed(GENERIC_FAILED_MESSAGE)
                }
            }
        }
    }

    private fun stopAndFinish() {
        replaceSession()
        finish()
    }

    private fun recordObservation(kind: PlaybackObservationKind) {
        try {
            observationRecorder.record(
                PlaybackObservation(
                    kind = kind,
                    attemptNumber = 0,
                    attemptLimit = EXTERNAL_ATTEMPT_LIMIT,
                    timestampEpochMillis = System.currentTimeMillis(),
                ),
            )
        } catch (_: Exception) {
            // Diagnostics must never affect the external playback flow.
        }
    }

    private companion object {
        const val EXTRA_TITLE_LITERAL = "title"
        const val DEFAULT_TITLE = "Внешний видеопоток"
        const val EXTERNAL_ATTEMPT_LIMIT = 1
        const val CONTROLLER_TIMEOUT_MILLIS = 20_000L
        const val COMMAND_TIMEOUT_MILLIS = 25_000L
    }
}

private sealed interface ExternalUiState {
    data object Starting : ExternalUiState
    data class IntentRejected(val reason: ExternalPlaybackIntentRejection) : ExternalUiState
    data class LanRationale(val host: String) : ExternalUiState
    data class LanDenied(val permanentlyDenied: Boolean) : ExternalUiState
    data class HttpApprovalRequired(val origin: String) : ExternalUiState
    data object Preparing : ExternalUiState
    data class Failed(val message: String) : ExternalUiState

    data class Playing(
        val controller: MediaController,
        val title: String,
    ) : ExternalUiState
}

@Composable
private fun ExternalPlaybackScreen(
    state: ExternalUiState,
    onApproveLan: () -> Unit,
    onRetryGate: () -> Unit,
    onApproveHttp: () -> Unit,
    onRetryPlayback: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        ExternalUiState.Starting,
        ExternalUiState.Preparing,
        -> ExternalPlaybackMessage(
            title = "Подготовка видеопотока…",
            onBack = onBack,
            modifier = modifier,
        )

        is ExternalUiState.IntentRejected -> ExternalPlaybackMessage(
            title = intentRejectionMessage(state.reason),
            onBack = onBack,
            modifier = modifier,
        )

        is ExternalUiState.LanRationale -> {
            val lanFocusRequester = remember(state.host) { FocusRequester() }
            LaunchedEffect(state.host) {
                withFrameNanos { }
                lanFocusRequester.requestFocus()
            }
            Column(
                modifier = modifier.fillMaxSize().padding(56.dp),
                verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
            ) {
                Text(
                    text = "Доступ к локальной сети",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Для воспроизведения потока из локальной сети Android 17 требует отдельное разрешение.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    MuxTvActionButton(
                        text = "Разрешить",
                        onClick = onApproveLan,
                        modifier = Modifier
                            .testTag(EXTERNAL_LAN_APPROVE_TEST_TAG)
                            .focusRequester(lanFocusRequester),
                    )
                    MuxTvActionButton(
                        text = "Назад",
                        onClick = onBack,
                        modifier = Modifier.testTag(EXTERNAL_BACK_TEST_TAG),
                    )
                }
            }
        }

        is ExternalUiState.LanDenied -> ExternalPlaybackMessage(
            title = if (state.permanentlyDenied) {
                "Доступ к локальной сети запрещён. Включите его в настройках Android и повторите запуск."
            } else {
                "Доступ к локальной сети не предоставлен."
            },
            onBack = onBack,
            onRetry = onRetryGate.takeIf { !state.permanentlyDenied },
            modifier = modifier,
        )

        is ExternalUiState.HttpApprovalRequired -> Column(
            modifier = modifier.fillMaxSize().padding(56.dp),
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
        ) {
            Text(
                text = "Этот поток использует незащищённое HTTP-соединение.",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Разрешить воспроизведение только для ${state.origin}?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                MuxTvActionButton(
                    text = "Разрешить для этого адреса",
                    onClick = onApproveHttp,
                    modifier = Modifier.testTag(EXTERNAL_HTTP_APPROVE_TEST_TAG),
                )
                MuxTvActionButton(
                    text = "Назад",
                    onClick = onBack,
                    modifier = Modifier.testTag(EXTERNAL_BACK_TEST_TAG),
                )
            }
        }

        is ExternalUiState.Failed -> ExternalPlaybackMessage(
            title = state.message,
            onRetry = onRetryPlayback,
            onBack = onBack,
            modifier = modifier,
        )

        is ExternalUiState.Playing -> ExternalPlaybackContent(
            controller = state.controller,
            title = state.title,
            onStop = onStop,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@AndroidXOptIn(UnstableApi::class)
@Composable
private fun ExternalPlaybackContent(
    controller: MediaController,
    title: String,
    onStop: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var isPlaying by remember(controller) { mutableStateOf(controller.isPlaying) }
    var playbackState by remember(controller) { mutableIntStateOf(controller.playbackState) }
    var hasError by remember(controller) { mutableStateOf(controller.playerError != null) }
    var controlsVisible by remember(controller) { mutableStateOf(false) }
    var lastInteractionNanos by remember(controller) { mutableLongStateOf(System.nanoTime()) }
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

    LaunchedEffect(controller, controlsVisible) {
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
            .testTag(EXTERNAL_SURFACE_TEST_TAG)
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
                    .testTag(EXTERNAL_OVERLAY_TEST_TAG)
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
                    MuxTvActionButton(
                        text = if (isPlaying) "Пауза" else "Продолжить",
                        onClick = {
                            registerInteraction()
                            if (isPlaying) controller.pause() else controller.play()
                        },
                        modifier = Modifier
                            .testTag(EXTERNAL_PRIMARY_ACTION_TEST_TAG)
                            .focusRequester(primaryActionFocusRequester),
                    )
                    MuxTvActionButton(
                        text = "Остановить",
                        onClick = {
                            registerInteraction()
                            onStop()
                        },
                    )
                    MuxTvActionButton(
                        text = "Назад",
                        onClick = {
                            registerInteraction()
                            onBack()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExternalPlaybackMessage(
    title: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
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
            onRetry?.let { retry ->
                MuxTvActionButton(
                    text = "Повторить",
                    onClick = retry,
                    modifier = Modifier
                        .testTag(EXTERNAL_RETRY_TEST_TAG)
                        .focusRequester(primaryFocusRequester),
                )
            }
            MuxTvActionButton(
                text = "Назад",
                onClick = onBack,
                modifier = Modifier
                    .testTag(EXTERNAL_BACK_TEST_TAG)
                    .then(
                        if (onRetry == null) {
                            Modifier.focusRequester(primaryFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

private fun intentRejectionMessage(reason: ExternalPlaybackIntentRejection): String = when (reason) {
    ExternalPlaybackIntentRejection.WrongAction ->
        "Некорректный запрос внешнего плеера."
    ExternalPlaybackIntentRejection.MissingUri,
    ExternalPlaybackIntentRejection.UnsupportedScheme,
    ExternalPlaybackIntentRejection.MissingHost,
    -> "Некорректный адрес видеопотока."
    ExternalPlaybackIntentRejection.EmbeddedCredentials ->
        "Адреса со встроенными учётными данными не поддерживаются."
    ExternalPlaybackIntentRejection.UnsupportedMimeType ->
        "Этот тип контента не поддерживается внешним плеером."
    ExternalPlaybackIntentRejection.UriTooLong ->
        "Адрес видеопотока слишком длинный."
    ExternalPlaybackIntentRejection.InvalidMetadata ->
        "Некорректные метаданные внешнего потока."
}

private fun externalFailureMessage(reason: ExternalPlaybackStartFailure): String = when (reason) {
    ExternalPlaybackStartFailure.InvalidDescriptor ->
        "Невозможно открыть адрес видеопотока."
    ExternalPlaybackStartFailure.CleartextNotApproved ->
        "Воспроизведение по HTTP не разрешено."
    ExternalPlaybackStartFailure.LeaseUnavailable ->
        "Сеанс внешнего воспроизведения истёк. Повторите запуск."
    ExternalPlaybackStartFailure.PlaybackFailed ->
        "Не удалось воспроизвести поток."
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

private const val INVALID_LOCATOR_MESSAGE = "Некорректный адрес видеопотока."
private const val APPROVAL_CAPACITY_MESSAGE =
    "Достигнут лимит HTTP-разрешений внешнего плеера."
private const val CONNECTION_FAILED_MESSAGE =
    "Не удалось подключиться к службе воспроизведения."
private const val GENERIC_FAILED_MESSAGE = "Не удалось подготовить внешний поток."
private const val EXTERNAL_SURFACE_TEST_TAG = "external-surface"
private const val EXTERNAL_OVERLAY_TEST_TAG = "external-overlay"
private const val EXTERNAL_PRIMARY_ACTION_TEST_TAG = "external-primary-action"
private const val EXTERNAL_BACK_TEST_TAG = "external-back"
private const val EXTERNAL_HTTP_APPROVE_TEST_TAG = "external-http-approve"
private const val EXTERNAL_LAN_APPROVE_TEST_TAG = "external-lan-approve"
private const val EXTERNAL_RETRY_TEST_TAG = "external-retry"
private const val OVERLAY_HIDE_NANOS = 6_000_000_000L

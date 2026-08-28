package app.muxtv.external

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.player.PlayerRemoteCommand
import app.muxtv.feature.player.PlayerRemoteInputHost
import app.muxtv.feature.player.PlayerSurfaceAction
import app.muxtv.feature.player.PlayerSurfaceContent
import app.muxtv.player.ExternalPlaybackDescriptor
import app.muxtv.player.ExternalPlaybackLeaseRegistry
import app.muxtv.player.ExternalPlaybackStartFailure
import app.muxtv.player.ExternalPlaybackStartResult
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import app.muxtv.player.PlaybackObservationRecorder
import app.muxtv.player.media3.Media3PlaybackSessionGateway
import app.muxtv.player.media3.Media3PlaybackSurface
import app.muxtv.player.media3.MediaControllerOperationException
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.PlaybackSetupId
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    lateinit var playbackSessionGateway: Media3PlaybackSessionGateway

    @Inject
    lateinit var observationRecorder: PlaybackObservationRecorder

    private val permissionGate = LocalNetworkPermissionGate(Build.VERSION.SDK_INT)
    private val remoteInputHost = PlayerRemoteInputHost()

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
                    remoteInputHost = remoteInputHost,
                    playbackSessionGateway = playbackSessionGateway,
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

    // EP-08 intentionally intercepts native Android TV D-pad events at the Activity boundary
    // before the focused Compose hierarchy. ComponentActivity inherits this public Activity
    // callback through an AndroidX restricted implementation, so keep the suppression local to
    // this documented dispatch boundary rather than weakening semantics by moving to onKeyDown.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val command = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> PlayerRemoteCommand.SEEK_BACKWARD
                KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerRemoteCommand.SEEK_FORWARD
                else -> null
            }
            if (command != null && remoteInputHost.dispatch(command)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
            sourcePackage = sourcePackageOf(intent),
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
        val displayTitle = parsed.displayTitle ?: DEFAULT_TITLE
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

                // The service intentionally completes the external setup only after
                // Player.Listener.onRenderedFirstFrame(). A video surface therefore has to be
                // attached before awaiting that command result; otherwise Activity and service
                // form a circular wait (Started -> render surface -> first frame -> Started).
                // SurfaceAttaching keeps the first-frame authority in the service while making
                // the user-visible surface available to Media3 during preparation.
                uiState = ExternalUiState.SurfaceAttaching(
                    controller = controller,
                    sessionId = leaseSessionId,
                    title = displayTitle,
                )

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
                        sessionId = leaseSessionId,
                        title = displayTitle,
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

    private fun sourcePackageOf(intent: Intent): String? {
        val referrerName = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
        val referrer = runCatching {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)
        }.getOrNull()
        val androidAppReferrer = when {
            referrer != null -> referrer
            referrerName != null -> runCatching { Uri.parse(referrerName) }.getOrNull()
            else -> null
        }
        return androidAppReferrer
            ?.takeIf { it.scheme == "android-app" }
            ?.host
            ?: callingPackage?.takeIf(String::isNotBlank)
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

    sealed interface Surface : ExternalUiState {
        val controller: MediaController
        val sessionId: String
        val title: String
    }

    data class SurfaceAttaching(
        override val controller: MediaController,
        override val sessionId: String,
        override val title: String,
    ) : Surface

    data class Playing(
        override val controller: MediaController,
        override val sessionId: String,
        override val title: String,
    ) : Surface
}

@Composable
private fun ExternalPlaybackScreen(
    state: ExternalUiState,
    remoteInputHost: PlayerRemoteInputHost,
    playbackSessionGateway: Media3PlaybackSessionGateway,
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
                    text = "Для воспроизведения потока из локальной сети требуется системное разрешение.",
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

        is ExternalUiState.HttpApprovalRequired -> {
            val httpApprovalFocusRequester = remember(state.origin) { FocusRequester() }
            LaunchedEffect(state.origin) {
                withFrameNanos { }
                httpApprovalFocusRequester.requestFocus()
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
                    text = "Разрешить воспроизведение только для ${state.origin}?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    MuxTvActionButton(
                        text = "Разрешить для этого адреса",
                        onClick = onApproveHttp,
                        modifier = Modifier
                            .testTag(EXTERNAL_HTTP_APPROVE_TEST_TAG)
                            .focusRequester(httpApprovalFocusRequester),
                    )
                    MuxTvActionButton(
                        text = "Назад",
                        onClick = onBack,
                        modifier = Modifier.testTag(EXTERNAL_BACK_TEST_TAG),
                    )
                }
            }
        }

        is ExternalUiState.Failed -> ExternalPlaybackMessage(
            title = state.message,
            onRetry = onRetryPlayback,
            onBack = onBack,
            modifier = modifier,
        )

        is ExternalUiState.Surface -> {
            val playbackSession = remember(state.controller, playbackSessionGateway) {
                playbackSessionGateway.sessionFor(state.controller)
            }
            DisposableEffect(playbackSession) {
                onDispose { playbackSession.close() }
            }
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .then(
                        if (state is ExternalUiState.Playing) {
                            Modifier.testTag(EXTERNAL_FIRST_FRAME_CONFIRMED_TEST_TAG)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                PlayerSurfaceContent(
                    session = playbackSession,
                    playbackSurface = { session, surfaceModifier ->
                        Media3PlaybackSurface(
                            session = session,
                            modifier = surfaceModifier,
                        )
                    },
                    title = state.title,
                    favoriteSupported = false,
                    contentIdentity = state.sessionId,
                    remoteInputHost = remoteInputHost,
                    stopAction = PlayerSurfaceAction(
                        label = "Остановить",
                        onClick = onStop,
                    ),
                    backAction = PlayerSurfaceAction(
                        label = "Назад",
                        onClick = onBack,
                    ),
                    testTagPrefix = "external",
                    modifier = Modifier.fillMaxSize(),
                )
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

private const val INVALID_LOCATOR_MESSAGE = "Некорректный адрес видеопотока."
private const val APPROVAL_CAPACITY_MESSAGE =
    "Достигнут лимит HTTP-разрешений внешнего плеера."
private const val CONNECTION_FAILED_MESSAGE =
    "Не удалось подключиться к службе воспроизведения."
private const val GENERIC_FAILED_MESSAGE = "Не удалось подготовить внешний поток."
private const val EXTERNAL_BACK_TEST_TAG = "external-back"
private const val EXTERNAL_FIRST_FRAME_CONFIRMED_TEST_TAG = "external-first-frame-confirmed"
private const val EXTERNAL_HTTP_APPROVE_TEST_TAG = "external-http-approve"
private const val EXTERNAL_LAN_APPROVE_TEST_TAG = "external-lan-approve"
private const val EXTERNAL_RETRY_TEST_TAG = "external-retry"

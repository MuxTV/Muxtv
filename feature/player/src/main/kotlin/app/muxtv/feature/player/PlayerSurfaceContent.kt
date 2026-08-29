package app.muxtv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.PlaybackControlSession
import app.muxtv.player.PlaybackSeekDirection
import app.muxtv.player.PlaybackSeekRejectReason
import app.muxtv.player.PlaybackSeekResult
import app.muxtv.player.PlaybackSessionPhase
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

typealias PlaybackSurfaceRenderer = @Composable (PlaybackControlSession, Modifier) -> Unit

data class PlayerSurfaceAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
) {
    init {
        require(label.isNotBlank())
    }
}

private enum class PlayerSheetKind { AUDIO, SUBTITLE }

internal enum class SeekInputOutcome(
    val diagnosticTag: String,
    val handlesDispatch: Boolean = false,
    val publishesSemanticOutcome: Boolean = true,
) {
    SUBMITTED(
        diagnosticTag = "submitted",
        handlesDispatch = true,
        publishesSemanticOutcome = false,
    ),
    SERVICE_ACCEPTED("accepted"),
    CONTROLS_VISIBLE("controls-visible"),
    SHEET_OPEN("sheet-open"),
    COMMAND_UNAVAILABLE("command-unavailable"),
    UNKNOWN_DURATION("unknown-duration"),
    LIVE_CONTENT("live-content"),
    INVALID_POSITION("invalid-position"),
    CONTROLLER_REJECTED("controller-rejected"),
}

private fun PlaybackSeekRejectReason.toInputOutcome(): SeekInputOutcome = when (this) {
    PlaybackSeekRejectReason.STALE_PLAYBACK,
    PlaybackSeekRejectReason.COMMAND_UNAVAILABLE,
    -> SeekInputOutcome.COMMAND_UNAVAILABLE
    PlaybackSeekRejectReason.LIVE_CONTENT -> SeekInputOutcome.LIVE_CONTENT
    PlaybackSeekRejectReason.UNKNOWN_DURATION -> SeekInputOutcome.UNKNOWN_DURATION
    PlaybackSeekRejectReason.INVALID_POSITION,
    PlaybackSeekRejectReason.INVALID_TARGET,
    -> SeekInputOutcome.INVALID_POSITION
    PlaybackSeekRejectReason.CONTROLLER_REJECTED -> SeekInputOutcome.CONTROLLER_REJECTED
}

/**
 * Single capability-driven fullscreen surface and overlay state machine.
 *
 * Shared by the catalog PlayerRoute and the external playback route. Hidden by default,
 * Center/OK reveals the overlay, inactivity auto-hides after 6 s, Back closes the overlay
 * first and only then falls through to the route/activity. Audio/subtitle actions and the
 * timeline appear only when the stable playback-session state supports them.
 *
 * Seek ownership is intentionally presentation-only here: this surface computes immediate
 * provisional HUD state and submits semantic requests, while the playback service owns the
 * coalescing scheduler and the only production player seek mutation.
 */
@Composable
fun PlayerSurfaceContent(
    session: PlaybackControlSession,
    playbackSurface: PlaybackSurfaceRenderer,
    title: String,
    favoriteSupported: Boolean,
    modifier: Modifier = Modifier,
    contentIdentity: Any = Unit,
    remoteInputHost: PlayerRemoteInputHost? = null,
    favoriteAction: PlayerFavoriteAction? = null,
    stopAction: PlayerSurfaceAction? = null,
    backAction: PlayerSurfaceAction? = null,
    testTagPrefix: String = "player",
) {
    val snapshot by session.state.collectAsState()
    val capabilities = snapshot.capabilities.copy(supportsFavorite = favoriteSupported)
    val audioModels = snapshot.audioTracks
    val subtitleModels = snapshot.subtitleTracks
    val surfaceScope = rememberCoroutineScope()

    var seekState by remember(contentIdentity) {
        mutableStateOf<SeekPresentationState>(SeekPresentationState.Idle)
    }
    var provisionalSeekTargetMs by remember(contentIdentity) { mutableStateOf<Long?>(null) }
    var seekRequestSequence by remember(contentIdentity) { mutableLongStateOf(0L) }
    var controlsVisible by remember(contentIdentity) { mutableStateOf(false) }
    var lastInteractionNanos by remember(contentIdentity) { mutableLongStateOf(System.nanoTime()) }
    var openSheet by remember(contentIdentity) { mutableStateOf<PlayerSheetKind?>(null) }
    var previouslyOpenSheet by remember(contentIdentity) { mutableStateOf<PlayerSheetKind?>(null) }
    var positionMs by remember(contentIdentity) { mutableLongStateOf(snapshot.timeline.positionMs) }
    var lastSeekInputOutcome by remember(contentIdentity) { mutableStateOf<SeekInputOutcome?>(null) }
    val primaryActionFocusRequester = remember(session) { FocusRequester() }
    val surfaceFocusRequester = remember { FocusRequester() }
    val audioActionFocusRequester = remember(contentIdentity) { FocusRequester() }
    val subtitleActionFocusRequester = remember(contentIdentity) { FocusRequester() }

    fun revealControls() {
        lastInteractionNanos = System.nanoTime()
        controlsVisible = true
    }

    fun registerInteraction() {
        lastInteractionNanos = System.nanoTime()
    }

    fun recordSeekInputOutcome(outcome: SeekInputOutcome): Boolean {
        if (outcome.publishesSemanticOutcome) {
            remoteInputHost?.recordSemanticOutcome(outcome.diagnosticTag)
        }
        lastSeekInputOutcome = outcome
        return outcome.handlesDispatch
    }

    fun requestSeek(direction: PlaybackSeekDirection): SeekInputOutcome {
        if (!capabilities.canSeek) return SeekInputOutcome.COMMAND_UNAVAILABLE
        if (capabilities.isLive) return SeekInputOutcome.LIVE_CONTENT

        val durationMs = snapshot.timeline.durationMs
            ?: return SeekInputOutcome.UNKNOWN_DURATION
        if (!capabilities.hasKnownDuration || durationMs <= 0L) {
            return SeekInputOutcome.UNKNOWN_DURATION
        }

        val currentPositionMs = positionMs.coerceAtLeast(snapshot.timeline.positionMs)
        if (currentPositionMs < 0L) return SeekInputOutcome.INVALID_POSITION

        val startMs = provisionalSeekTargetMs ?: currentPositionMs
        val targetMs = (
            startMs + direction.sign * SEEK_STEP_MILLIS
        ).coerceIn(0L, durationMs)
        provisionalSeekTargetMs = targetMs
        seekState = SeekPresentationState.Pending(targetMs, direction)
        seekRequestSequence += 1L
        val requestSequence = seekRequestSequence

        surfaceScope.launch {
            val result = try {
                session.seekRelative(
                    direction = direction,
                    timeoutMillis = SEEK_REQUEST_TIMEOUT_MILLIS,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (requestSequence != seekRequestSequence) return@launch
            when (result) {
                is PlaybackSeekResult.Accepted -> {
                    provisionalSeekTargetMs = result.targetMs
                    seekState = SeekPresentationState.Applying(
                        targetMs = result.targetMs,
                        direction = result.direction,
                    )
                    recordSeekInputOutcome(SeekInputOutcome.SERVICE_ACCEPTED)
                }
                is PlaybackSeekResult.Rejected -> {
                    provisionalSeekTargetMs = null
                    seekState = SeekPresentationState.Idle
                    recordSeekInputOutcome(result.reason.toInputOutcome())
                }
                null -> {
                    provisionalSeekTargetMs = null
                    seekState = SeekPresentationState.Idle
                    recordSeekInputOutcome(SeekInputOutcome.CONTROLLER_REJECTED)
                }
            }
        }
        return SeekInputOutcome.SUBMITTED
    }

    fun handleSeekInput(direction: PlaybackSeekDirection): Boolean =
        recordSeekInputOutcome(requestSeek(direction))

    val durationMs = snapshot.timeline.durationMs
    val showTimeline = capabilities.hasKnownDuration && !capabilities.isLive && durationMs != null
    val showAudioAction = capabilities.hasAudioTracks && capabilities.canSetTrackSelection
    val showSubtitleAction = capabilities.hasTextTracks && capabilities.canSetTrackSelection
    val currentRemoteInputHandler by rememberUpdatedState(
        newValue = { command: PlayerRemoteCommand ->
            when {
                controlsVisible -> recordSeekInputOutcome(SeekInputOutcome.CONTROLS_VISIBLE)
                openSheet != null -> recordSeekInputOutcome(SeekInputOutcome.SHEET_OPEN)
                else -> when (command) {
                    PlayerRemoteCommand.SEEK_BACKWARD -> handleSeekInput(
                        PlaybackSeekDirection.BACKWARD,
                    )
                    PlayerRemoteCommand.SEEK_FORWARD -> handleSeekInput(
                        PlaybackSeekDirection.FORWARD,
                    )
                }
            }
        },
    )

    DisposableEffect(remoteInputHost, contentIdentity) {
        val registration = remoteInputHost?.attach { command ->
            currentRemoteInputHandler(command)
        }
        onDispose { registration?.close() }
    }

    LaunchedEffect(snapshot.timeline.positionMs, contentIdentity) {
        if (seekState is SeekPresentationState.Idle) {
            positionMs = snapshot.timeline.positionMs
        }
    }

    LaunchedEffect(seekState) {
        val completed = seekState as? SeekPresentationState.Completed ?: return@LaunchedEffect
        delay(SEEK_HUD_LINGER_MILLIS)
        if (seekState == completed) seekState = SeekPresentationState.Idle
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

    LaunchedEffect(openSheet) {
        if (openSheet == null && previouslyOpenSheet != null) {
            withFrameNanos { }
            when (previouslyOpenSheet) {
                PlayerSheetKind.AUDIO -> audioActionFocusRequester.requestFocus()
                PlayerSheetKind.SUBTITLE -> subtitleActionFocusRequester.requestFocus()
                null -> Unit
            }
        }
        previouslyOpenSheet = openSheet
    }

    LaunchedEffect(showAudioAction, showSubtitleAction) {
        if (openSheet == PlayerSheetKind.AUDIO && !showAudioAction) openSheet = null
        if (openSheet == PlayerSheetKind.SUBTITLE && !showSubtitleAction) openSheet = null
    }

    LaunchedEffect(contentIdentity, controlsVisible, openSheet) {
        if (!controlsVisible || openSheet != null) return@LaunchedEffect
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

    LaunchedEffect(controlsVisible, showTimeline, seekState, contentIdentity, session) {
        val seekNeedsSampling = seekState is SeekPresentationState.Pending ||
            seekState is SeekPresentationState.Applying
        if ((!controlsVisible || !showTimeline) && !seekNeedsSampling) return@LaunchedEffect
        while (isActive) {
            val sampled = try {
                session.currentTimeline()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (sampled != null) {
                positionMs = sampled.positionMs
                val applying = seekState as? SeekPresentationState.Applying
                if (applying != null &&
                    abs(sampled.positionMs - applying.targetMs) <= SEEK_APPLIED_TOLERANCE_MILLIS
                ) {
                    provisionalSeekTargetMs = null
                    seekState = SeekPresentationState.Completed(
                        targetMs = sampled.positionMs,
                        direction = applying.direction,
                    )
                }
            }
            delay(POSITION_SAMPLE_MILLIS)
        }
    }

    BackHandler(enabled = controlsVisible) {
        registerInteraction()
        controlsVisible = false
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = TvTokens.Motion.overlayInMillis,
            easing = TvTokens.Motion.easeOut,
        ),
        label = "playerOverlayAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("$testTagPrefix-surface")
            .onPreviewKeyEvent { event ->
                if (!controlsVisible && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> handleSeekInput(PlaybackSeekDirection.BACKWARD)
                        Key.DirectionRight -> handleSeekInput(PlaybackSeekDirection.FORWARD)
                        else -> false
                    }
                } else {
                    false
                }
            }
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
        playbackSurface(session, Modifier.fillMaxSize())

        lastSeekInputOutcome?.let { outcome ->
            Box(
                modifier = Modifier.testTag(
                    "$testTagPrefix-seek-input-${outcome.diagnosticTag}",
                ),
            )
        }

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = overlayAlpha }
                    .background(TvTokens.Color.surfaceRaised.copy(alpha = 0.94f))
                    .padding(horizontal = 48.dp, vertical = 24.dp)
                    .testTag("$testTagPrefix-overlay")
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
                    text = playbackStatus(
                        phase = snapshot.phase,
                        hasError = snapshot.hasError,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showTimeline && durationMs != null) {
                    var timelineFocused by remember(contentIdentity) { mutableStateOf(false) }
                    PlaybackTimeline(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        previewState = seekState,
                        testTagPrefix = testTagPrefix,
                        modifier = Modifier
                            .focusable()
                            .onFocusChanged { timelineFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && timelineFocused) {
                                    when (event.key) {
                                        Key.DirectionLeft -> handleSeekInput(
                                            PlaybackSeekDirection.BACKWARD,
                                        )
                                        Key.DirectionRight -> handleSeekInput(
                                            PlaybackSeekDirection.FORWARD,
                                        )
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    favoriteAction?.let { favorite ->
                        MuxTvActionButton(
                            text = favorite.label,
                            onClick = {
                                registerInteraction()
                                favorite.onClick()
                            },
                            enabled = favorite.enabled,
                            modifier = Modifier.testTag("$testTagPrefix-favorite"),
                        )
                    }
                    MuxTvActionButton(
                        text = if (snapshot.isPlaying) "Пауза" else "Продолжить",
                        onClick = {
                            registerInteraction()
                            if (snapshot.isPlaying) session.pause() else session.play()
                        },
                        modifier = Modifier
                            .testTag("$testTagPrefix-primary-action")
                            .focusRequester(primaryActionFocusRequester),
                    )
                    if (showAudioAction) {
                        MuxTvActionButton(
                            text = TrackLabelFormatter.audioActionLabel(audioModels),
                            onClick = {
                                registerInteraction()
                                openSheet = PlayerSheetKind.AUDIO
                            },
                            modifier = Modifier
                                .testTag("$testTagPrefix-audio")
                                .focusRequester(audioActionFocusRequester),
                        )
                    }
                    if (showSubtitleAction) {
                        MuxTvActionButton(
                            text = TrackLabelFormatter.subtitleActionLabel(
                                models = subtitleModels,
                                textDisabled = snapshot.subtitlesDisabled,
                            ),
                            onClick = {
                                registerInteraction()
                                openSheet = PlayerSheetKind.SUBTITLE
                            },
                            modifier = Modifier
                                .testTag("$testTagPrefix-subtitle")
                                .focusRequester(subtitleActionFocusRequester),
                        )
                    }
                    stopAction?.let { stop ->
                        MuxTvActionButton(
                            text = stop.label,
                            onClick = {
                                registerInteraction()
                                stop.onClick()
                            },
                            enabled = stop.enabled,
                            modifier = Modifier.testTag("$testTagPrefix-stop"),
                        )
                    }
                    backAction?.let { back ->
                        MuxTvActionButton(
                            text = back.label,
                            onClick = {
                                registerInteraction()
                                back.onClick()
                            },
                            enabled = back.enabled,
                            modifier = Modifier.testTag("$testTagPrefix-back"),
                        )
                    }
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

        if (!controlsVisible && seekState !is SeekPresentationState.Idle) {
            SeekHud(
                state = seekState,
                modifier = Modifier.align(Alignment.Center),
                testTag = "$testTagPrefix-seek-hud",
            )
        }

        when (openSheet) {
            PlayerSheetKind.AUDIO -> AudioTrackSheet(
                models = audioModels,
                onSelect = { model -> session.selectAudioTrack(model.key) },
                onDismiss = {
                    registerInteraction()
                    openSheet = null
                },
                sheetTestTag = "$testTagPrefix-audio-sheet",
                rowTestTagPrefix = "$testTagPrefix-audio-track",
            )

            PlayerSheetKind.SUBTITLE -> SubtitleTrackSheet(
                models = subtitleModels,
                textDisabled = snapshot.subtitlesDisabled,
                onSelect = { model -> session.selectSubtitleTrack(model.key) },
                onSelectOff = session::disableSubtitles,
                onDismiss = {
                    registerInteraction()
                    openSheet = null
                },
                sheetTestTag = "$testTagPrefix-subtitle-sheet",
                rowTestTagPrefix = "$testTagPrefix-subtitle-track",
            )

            null -> Unit
        }
    }
}

@Composable
private fun PlaybackTimeline(
    positionMs: Long,
    durationMs: Long,
    previewState: SeekPresentationState,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val displayMs = when (previewState) {
        is SeekPresentationState.Pending -> previewState.targetMs
        is SeekPresentationState.Applying -> previewState.targetMs
        is SeekPresentationState.Completed -> previewState.targetMs
        SeekPresentationState.Idle -> positionMs
    }
    val direction = when (previewState) {
        is SeekPresentationState.Pending -> previewState.direction
        is SeekPresentationState.Applying -> previewState.direction
        is SeekPresentationState.Completed -> previewState.direction
        SeekPresentationState.Idle -> null
    }
    val timePrefix = if (previewState is SeekPresentationState.Idle) {
        ""
    } else {
        when (direction) {
            PlaybackSeekDirection.BACKWARD -> "← "
            PlaybackSeekDirection.FORWARD -> "→ "
            null -> ""
        }
    }
    val progress = if (durationMs > 0L) {
        (displayMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.xSmall),
    ) {
        Text(
            text = timePrefix + TrackLabelFormatter.formatPlaybackTime(displayMs) + " / " +
                TrackLabelFormatter.formatPlaybackTime(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("$testTagPrefix-timeline-time"),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                .testTag("$testTagPrefix-timeline"),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun playbackStatus(
    phase: PlaybackSessionPhase,
    hasError: Boolean,
): String = when {
    hasError -> "Ошибка воспроизведения"
    phase == PlaybackSessionPhase.BUFFERING -> "Буферизация"
    phase == PlaybackSessionPhase.READY -> "Готово"
    phase == PlaybackSessionPhase.ENDED -> "Поток завершён"
    else -> "Подготовка"
}

private const val OVERLAY_HIDE_NANOS = 6_000_000_000L
private const val POSITION_SAMPLE_MILLIS = 500L
private const val SEEK_REQUEST_TIMEOUT_MILLIS = 2_000L
private const val SEEK_STEP_MILLIS = 10_000L
private const val SEEK_HUD_LINGER_MILLIS = 1_500L
private const val SEEK_APPLIED_TOLERANCE_MILLIS = 2_000L

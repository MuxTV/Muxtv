package app.muxtv.feature.player

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.media3.Media3TrackController
import app.muxtv.player.media3.PlaybackSeekController
import app.muxtv.player.media3.SeekControllerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

private enum class SeekInputOutcome(val diagnosticTag: String) {
    ACCEPTED("accepted"),
    CONTROLS_VISIBLE("controls-visible"),
    SHEET_OPEN("sheet-open"),
    COMMAND_UNAVAILABLE("command-unavailable"),
    UNKNOWN_DURATION("unknown-duration"),
    LIVE_CONTENT("live-content"),
    INVALID_POSITION("invalid-position"),
    CONTROLLER_REJECTED("controller-rejected"),
}

/**
 * Single capability-driven fullscreen surface and overlay state machine.
 *
 * Shared by the catalog PlayerRoute and the external playback route. Hidden by default,
 * Center/OK reveals the overlay, inactivity auto-hides after 6 s, Back closes the overlay
 * first and only then falls through to the route/activity. Audio/subtitle actions and the
 * timeline appear only when the current Media3 state supports them.
 */
@AndroidXOptIn(UnstableApi::class)
@Composable
fun PlayerSurfaceContent(
    controller: MediaController,
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
    val capabilities = rememberPlayerCapabilities(
        controller = controller,
        favoriteSupported = favoriteSupported,
    )
    val audioModels = rememberAudioTrackModels(controller)
    val subtitleModels = rememberSubtitleTrackModels(controller)
    val surfaceScope = rememberCoroutineScope()
    val seekController = remember(contentIdentity) {
        PlaybackSeekController(
            scope = surfaceScope,
            onApplySeek = { generation, targetMs ->
                if (generation == contentIdentity) {
                    runCatching { controller.seekTo(targetMs) }
                }
            },
        )
    }
    val seekState by seekController.state.collectAsState()

    DisposableEffect(contentIdentity) {
        onDispose { seekController.reset() }
    }

    var isPlaying by remember(controller) { mutableStateOf(controller.isPlaying) }
    var playbackState by remember(controller) { mutableIntStateOf(controller.playbackState) }
    var hasError by remember(controller) { mutableStateOf(controller.playerError != null) }
    var controlsVisible by remember(contentIdentity) { mutableStateOf(false) }
    var lastInteractionNanos by remember(contentIdentity) { mutableLongStateOf(System.nanoTime()) }
    var openSheet by remember(contentIdentity) { mutableStateOf<PlayerSheetKind?>(null) }
    var previouslyOpenSheet by remember(contentIdentity) { mutableStateOf<PlayerSheetKind?>(null) }
    var positionMs by remember(contentIdentity) { mutableLongStateOf(0L) }
    var lastSeekInputOutcome by remember(contentIdentity) { mutableStateOf<SeekInputOutcome?>(null) }
    val primaryActionFocusRequester = remember(controller) { FocusRequester() }
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

    fun requestSeek(direction: Int): SeekInputOutcome {
        if (!capabilities.canSeek) return SeekInputOutcome.COMMAND_UNAVAILABLE
        if (capabilities.isLive) return SeekInputOutcome.LIVE_CONTENT

        val durationMs = controller.duration
        if (!capabilities.hasKnownDuration || durationMs == C.TIME_UNSET || durationMs <= 0L) {
            return SeekInputOutcome.UNKNOWN_DURATION
        }

        val currentPositionMs = controller.currentPosition
        if (currentPositionMs < 0L) return SeekInputOutcome.INVALID_POSITION

        return if (
            seekController.onDirectionRequested(
                generation = contentIdentity,
                direction = direction,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
            )
        ) {
            SeekInputOutcome.ACCEPTED
        } else {
            SeekInputOutcome.CONTROLLER_REJECTED
        }
    }

    fun recordSeekInputOutcome(outcome: SeekInputOutcome): Boolean {
        lastSeekInputOutcome = outcome
        return outcome == SeekInputOutcome.ACCEPTED
    }

    fun handleSeekInput(direction: Int): Boolean = recordSeekInputOutcome(requestSeek(direction))

    val showTimeline = capabilities.hasKnownDuration && !capabilities.isLive
    val showAudioAction = capabilities.hasAudioTracks && capabilities.canSetTrackSelection
    val showSubtitleAction = capabilities.hasTextTracks && capabilities.canSetTrackSelection
    val currentRemoteInputHandler by rememberUpdatedState(
        newValue = { command: PlayerRemoteCommand ->
            when {
                controlsVisible -> recordSeekInputOutcome(SeekInputOutcome.CONTROLS_VISIBLE)
                openSheet != null -> recordSeekInputOutcome(SeekInputOutcome.SHEET_OPEN)
                else -> when (command) {
                    PlayerRemoteCommand.SEEK_BACKWARD -> handleSeekInput(
                        PlaybackSeekController.DIRECTION_BACKWARD,
                    )

                    PlayerRemoteCommand.SEEK_FORWARD -> handleSeekInput(
                        PlaybackSeekController.DIRECTION_FORWARD,
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

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekController.onSeekConfirmed(contentIdentity)
                }
            }
        }
        controller.runOnApplicationThread { controller.addListener(listener) }
        onDispose { controller.runOnApplicationThread { controller.removeListener(listener) } }
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

    LaunchedEffect(controlsVisible, showTimeline, contentIdentity) {
        if (!controlsVisible || !showTimeline) return@LaunchedEffect
        while (isActive) {
            positionMs = controller.currentPosition
            delay(POSITION_SAMPLE_MILLIS)
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
            .testTag("$testTagPrefix-surface")
            .onPreviewKeyEvent { event ->
                if (!controlsVisible && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> handleSeekInput(
                            PlaybackSeekController.DIRECTION_BACKWARD,
                        )

                        Key.DirectionRight -> handleSeekInput(
                            PlaybackSeekController.DIRECTION_FORWARD,
                        )

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
        PlayerSurface(
            player = controller,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        )

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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
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
                    text = playbackStatus(playbackState = playbackState, hasError = hasError),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showTimeline) {
                    var timelineFocused by remember(contentIdentity) { mutableStateOf(false) }
                    PlaybackTimeline(
                        positionMs = positionMs,
                        durationMs = controller.duration,
                        previewState = seekState,
                        testTagPrefix = testTagPrefix,
                        modifier = Modifier
                            .focusable()
                            .onFocusChanged { timelineFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && timelineFocused) {
                                    when (event.key) {
                                        Key.DirectionLeft -> handleSeekInput(
                                            PlaybackSeekController.DIRECTION_BACKWARD,
                                        )

                                        Key.DirectionRight -> handleSeekInput(
                                            PlaybackSeekController.DIRECTION_FORWARD,
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
                        text = if (isPlaying) "Пауза" else "Продолжить",
                        onClick = {
                            registerInteraction()
                            if (isPlaying) controller.pause() else controller.play()
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
                                models = subtitleModels.tracks,
                                textDisabled = subtitleModels.textDisabled,
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

        if (!controlsVisible && seekState !is SeekControllerState.Idle) {
            SeekHud(
                state = seekState,
                modifier = Modifier.align(Alignment.Center),
                testTag = "$testTagPrefix-seek-hud",
            )
        }

        when (openSheet) {
            PlayerSheetKind.AUDIO -> AudioTrackSheet(
                models = audioModels,
                onSelect = { model ->
                    Media3TrackController.selectAudioTrack(
                        controller = controller,
                        groupId = model.key.groupId,
                        trackIndex = model.key.trackIndex,
                    )
                },
                onDismiss = {
                    registerInteraction()
                    openSheet = null
                },
                sheetTestTag = "$testTagPrefix-audio-sheet",
                rowTestTagPrefix = "$testTagPrefix-audio-track",
            )

            PlayerSheetKind.SUBTITLE -> SubtitleTrackSheet(
                models = subtitleModels.tracks,
                textDisabled = subtitleModels.textDisabled,
                onSelect = { model ->
                    Media3TrackController.selectTextTrack(
                        controller = controller,
                        groupId = model.key.groupId,
                        trackIndex = model.key.trackIndex,
                    )
                },
                onSelectOff = { Media3TrackController.disableTextTracks(controller) },
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
    previewState: SeekControllerState,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val displayMs = when (previewState) {
        is SeekControllerState.Pending -> previewState.targetMs
        is SeekControllerState.Applying -> previewState.targetMs
        is SeekControllerState.Completed -> previewState.targetMs
        SeekControllerState.Idle -> positionMs
    }
    val direction = when (previewState) {
        is SeekControllerState.Pending -> previewState.direction
        is SeekControllerState.Applying -> previewState.direction
        is SeekControllerState.Completed -> previewState.direction
        SeekControllerState.Idle -> PlaybackSeekController.DIRECTION_NONE
    }
    val timePrefix = if (previewState is SeekControllerState.Idle) {
        ""
    } else {
        when {
            direction < 0 -> "← "
            direction > 0 -> "→ "
            else -> ""
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
    playbackState: Int,
    hasError: Boolean,
): String = when {
    hasError -> "Ошибка воспроизведения"
    playbackState == Player.STATE_BUFFERING -> "Буферизация"
    playbackState == Player.STATE_READY -> "Готово"
    playbackState == Player.STATE_ENDED -> "Поток завершён"
    else -> "Подготовка"
}

private const val OVERLAY_HIDE_NANOS = 6_000_000_000L
private const val POSITION_SAMPLE_MILLIS = 500L
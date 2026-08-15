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

/**
 * Single capability-driven fullscreen surface and overlay state machine.
 *
 * Shared by the catalog PlayerRoute and the external playback route. Hidden by default,
 * Center/OK reveals the overlay, inactivity auto-hides after 6 s, Back closes the overlay
 * first and only then falls through to the route/activity. Audio/subtitle actions appear only
 * when the current Media3 state supports them.
 */
@AndroidXOptIn(UnstableApi::class)
@Composable
fun PlayerSurfaceContent(
    controller: MediaController,
    title: String,
    favoriteSupported: Boolean,
    modifier: Modifier = Modifier,
    contentIdentity: Any = Unit,
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

    var isPlaying by remember(controller) { mutableStateOf(controller.isPlaying) }
    var playbackState by remember(controller) { mutableIntStateOf(controller.playbackState) }
    var hasError by remember(controller) { mutableStateOf(controller.playerError != null) }
    var controlsVisible by remember(contentIdentity) { mutableStateOf(false) }
    var lastInteractionNanos by remember(contentIdentity) { mutableLongStateOf(System.nanoTime()) }
    var openSheet by remember(contentIdentity) { mutableStateOf<PlayerSheetKind?>(null) }
    var previouslyOpenSheet by remember(contentIdentity) { mutableStateOf<PlayerSheetKind?>(null) }
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

    val showAudioAction = capabilities.hasAudioTracks && capabilities.canSetTrackSelection
    val showSubtitleAction = capabilities.hasTextTracks && capabilities.canSetTrackSelection

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

    BackHandler(enabled = controlsVisible) {
        registerInteraction()
        controlsVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("$testTagPrefix-surface")
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

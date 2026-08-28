package app.muxtv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.session.MediaController
import app.muxtv.player.PlaybackControlSession
import app.muxtv.player.PlaybackSeekDirection
import app.muxtv.player.PlaybackSeekRejectReason
import app.muxtv.player.PlaybackSeekResult
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionSnapshot
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.PlaybackTimelineState
import app.muxtv.player.PlayerCapabilities
import app.muxtv.player.TrackKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Engine-neutral fixture for app instrumentation tests that exercise only the shared player
 * overlay. The controller is retained solely as the old test's content identity; no Media3 type
 * is passed into production [PlayerSurfaceContent].
 */
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
    val session = remember(controller) { EmptyPlayerSurfaceTestSession() }
    PlayerSurfaceContent(
        session = session,
        playbackSurface = { _, _ -> },
        title = title,
        favoriteSupported = favoriteSupported,
        modifier = modifier,
        contentIdentity = contentIdentity,
        remoteInputHost = remoteInputHost,
        favoriteAction = favoriteAction,
        stopAction = stopAction,
        backAction = backAction,
        testTagPrefix = testTagPrefix,
    )
}

private class EmptyPlayerSurfaceTestSession : PlaybackControlSession {
    private val mutableState = MutableStateFlow(
        PlaybackSessionSnapshot(
            phase = PlaybackSessionPhase.IDLE,
            isPlaying = false,
            hasError = false,
            capabilities = PlayerCapabilities(
                canSeek = false,
                canPause = false,
                canSetTrackSelection = false,
                hasAudioTracks = false,
                hasTextTracks = false,
                supportsFavorite = false,
                hasKnownDuration = false,
                isLive = false,
            ),
            timeline = PlaybackTimelineState(
                positionMs = 0L,
                durationMs = null,
                isLive = false,
            ),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            subtitlesDisabled = true,
        ),
    )

    override val state: StateFlow<PlaybackSessionSnapshot> = mutableState

    override suspend fun start(
        request: PlaybackStartRequest,
        timeoutMillis: Long,
    ): PlaybackStartResult = PlaybackStartResult.Started

    override fun play() = Unit
    override fun pause() = Unit
    override fun stop() = Unit

    override suspend fun currentTimeline(): PlaybackTimelineState = state.value.timeline

    override suspend fun seekRelative(
        direction: PlaybackSeekDirection,
        timeoutMillis: Long,
    ): PlaybackSeekResult = PlaybackSeekResult.Rejected(
        PlaybackSeekRejectReason.COMMAND_UNAVAILABLE,
    )

    override fun selectAudioTrack(key: TrackKey) = Unit
    override fun selectSubtitleTrack(key: TrackKey) = Unit
    override fun disableSubtitles() = Unit
}

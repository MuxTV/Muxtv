package app.muxtv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.player.PlayerSurfaceContent
import app.muxtv.player.PlaybackControlSession
import app.muxtv.player.PlaybackSeekDirection
import app.muxtv.player.PlaybackSeekResult
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionSnapshot
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.PlaybackTimelineState
import app.muxtv.player.PlayerCapabilities
import app.muxtv.player.TrackKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSeekHudStablePortJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hiddenSurfaceSeekUsesStableSessionAndShowsProvisionalTarget() {
        val session = SeekableTestSession(positionMs = 115_000L, durationMs = 300_000L)

        composeRule.setContent {
            MuxTvTheme {
                PlayerSurfaceContent(
                    session = session,
                    playbackSurface = { _, _ -> },
                    title = "Seekable stream",
                    favoriteSupported = false,
                    testTagPrefix = "player",
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("player-surface").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-surface").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("player-seek-hud").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-seek-hud")
            .assertTextContains("→")
            .assertTextContains("2:05")
        composeRule.onNodeWithTag("player-seek-input-submitted").assertExists()
    }

    @Test
    fun nonSeekableStableSessionDoesNotExposeSeekHud() {
        val session = SeekableTestSession(
            positionMs = 0L,
            durationMs = null,
            canSeek = false,
        )

        composeRule.setContent {
            MuxTvTheme {
                PlayerSurfaceContent(
                    session = session,
                    playbackSurface = { _, _ -> },
                    title = "Live stream",
                    favoriteSupported = false,
                    testTagPrefix = "player",
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("player-surface").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-surface").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-seek-hud").assertDoesNotExist()
        composeRule.onNodeWithTag("player-seek-input-command-unavailable").assertExists()
    }

    private class SeekableTestSession(
        positionMs: Long,
        durationMs: Long?,
        canSeek: Boolean = true,
    ) : PlaybackControlSession {
        private val mutableState = MutableStateFlow(
            PlaybackSessionSnapshot(
                phase = PlaybackSessionPhase.READY,
                isPlaying = false,
                hasError = false,
                capabilities = PlayerCapabilities(
                    canSeek = canSeek,
                    canPause = true,
                    canSetTrackSelection = false,
                    hasAudioTracks = false,
                    hasTextTracks = false,
                    supportsFavorite = false,
                    hasKnownDuration = durationMs != null,
                    isLive = durationMs == null,
                ),
                timeline = PlaybackTimelineState(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isLive = durationMs == null,
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
        ): PlaybackSeekResult {
            val current = state.value.timeline
            val duration = current.durationMs ?: return PlaybackSeekResult.Rejected(
                app.muxtv.player.PlaybackSeekRejectReason.UNKNOWN_DURATION,
            )
            val target = (current.positionMs + direction.sign * 10_000L).coerceIn(0L, duration)
            delay(250L)
            val updatedTimeline = current.copy(positionMs = target)
            mutableState.value = mutableState.value.copy(timeline = updatedTimeline)
            return PlaybackSeekResult.Accepted(targetMs = target, direction = direction)
        }

        override fun selectAudioTrack(key: TrackKey) = Unit
        override fun selectSubtitleTrack(key: TrackKey) = Unit
        override fun disableSubtitles() = Unit
    }
}

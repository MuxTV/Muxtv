package app.muxtv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.player.AudioTrackSheet
import app.muxtv.feature.player.SubtitleTrackSheet
import app.muxtv.player.AudioTrackUiModel
import app.muxtv.player.SubtitleTrackUiModel
import app.muxtv.player.TrackKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackSelectionSheetJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun audioSheetStartsWithFocusOnTheSelectedTrack() {
        composeRule.setContent {
            MuxTvTheme {
                AudioTrackSheet(
                    models = AUDIO_MODELS,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-audio-track-1")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onNodeWithTag("player-audio-track-1").isFocusedNow()
        }
    }

    @Test
    fun audioSheetSelectionInvokesCallbackWithTheTrackKey() {
        var selected: TrackKey? = null
        composeRule.setContent {
            MuxTvTheme {
                AudioTrackSheet(
                    models = AUDIO_MODELS,
                    onSelect = { selected = it.key },
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-audio-track-0")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-audio-track-0").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            selected == AUDIO_MODELS[0].key
        }
    }

    @Test
    fun focusNavigationSkipsUnsupportedTracks() {
        composeRule.setContent {
            MuxTvTheme {
                AudioTrackSheet(
                    models = AUDIO_MODELS,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-audio-track-1")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-audio-track-1").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onNodeWithTag("player-audio-track-3").isFocusedNow()
        }
    }

    @Test
    fun longStudioLabelIsFullyPresentInTheRowSemantics() {
        composeRule.setContent {
            MuxTvTheme {
                AudioTrackSheet(
                    models = AUDIO_MODELS,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-audio-track-0")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-audio-track-0")
            .assertTextContains(LONG_STUDIO_LABEL)
    }

    @Test
    fun subtitleSheetShowsOffRowSelectedWhenSubtitlesDisabled() {
        composeRule.setContent {
            MuxTvTheme {
                SubtitleTrackSheet(
                    models = SUBTITLE_MODELS,
                    textDisabled = true,
                    onSelect = {},
                    onSelectOff = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-subtitle-track-0")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onNodeWithTag("player-subtitle-track-0").isFocusedNow()
        }
        composeRule.onNodeWithTag("player-subtitle-track-0")
            .assertTextContains("Выключить")
            .assertTextContains("●")
    }

    @Test
    fun subtitleOffRowInvokesOffCallback() {
        var offSelected = false
        composeRule.setContent {
            MuxTvTheme {
                SubtitleTrackSheet(
                    models = SUBTITLE_MODELS,
                    textDisabled = false,
                    onSelect = {},
                    onSelectOff = { offSelected = true },
                    onDismiss = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-subtitle-track-0")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-subtitle-track-0").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        composeRule.waitUntil(timeoutMillis = 20_000) { offSelected }
    }

    @Test
    fun backClosesTheSheetThroughDismissCallback() {
        var dismissed = false
        composeRule.setContent {
            MuxTvTheme {
                AudioTrackSheet(
                    models = AUDIO_MODELS,
                    onSelect = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-audio-track-1")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 20_000) { dismissed }
    }

    private fun SemanticsNodeInteraction.isFocusedNow(): Boolean =
        fetchSemanticsNode().config[SemanticsProperties.Focused] == true

    private companion object {
        val LONG_STUDIO_LABEL =
            "Русский — Дублированный | HDRezka Studio | Авторский одноголосый перевод Гоблина"

        val AUDIO_MODELS = listOf(
            AudioTrackUiModel(
                key = TrackKey("g1", 0),
                selected = false,
                supported = true,
                primaryLabel = LONG_STUDIO_LABEL,
                languageLabel = "русский",
                technicalLabel = "E-AC-3 • 5.1 • 48 kHz • 640 kb/s",
                isDefault = true,
                isForced = false,
            ),
            AudioTrackUiModel(
                key = TrackKey("g1", 1),
                selected = true,
                supported = true,
                primaryLabel = "LostFilm / NewStudio",
                languageLabel = null,
                technicalLabel = "AC-3 • 5.1 • 48 kHz",
                isDefault = false,
                isForced = false,
            ),
            AudioTrackUiModel(
                key = TrackKey("g1", 2),
                selected = false,
                supported = false,
                primaryLabel = "English — Original",
                languageLabel = "английский",
                technicalLabel = "TrueHD • 7.1 • 48 kHz",
                isDefault = false,
                isForced = false,
            ),
            AudioTrackUiModel(
                key = TrackKey("g1", 3),
                selected = false,
                supported = true,
                primaryLabel = "Director Commentary",
                languageLabel = null,
                technicalLabel = "AC-3 • Stereo",
                isDefault = false,
                isForced = false,
            ),
        )

        val SUBTITLE_MODELS = listOf(
            SubtitleTrackUiModel(
                key = TrackKey("s1", 0),
                selected = false,
                supported = true,
                primaryLabel = "Русские субтитры",
                languageLabel = null,
                technicalLabel = "SRT • русский",
                isForced = false,
            ),
        )
    }
}

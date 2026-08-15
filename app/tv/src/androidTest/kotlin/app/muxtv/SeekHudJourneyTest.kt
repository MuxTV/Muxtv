package app.muxtv

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.player.SeekHud
import app.muxtv.player.media3.SeekControllerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekHudJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingStateShowsDirectionArrowAndVirtualTarget() {
        composeRule.setContent {
            MuxTvTheme {
                SeekHud(
                    state = SeekControllerState.Pending(
                        targetMs = 125_000L,
                        direction = 1,
                    ),
                    testTag = "player-seek-hud",
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-seek-hud")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-seek-hud")
            .assertTextContains("→")
            .assertTextContains("2:05")
    }

    @Test
    fun backwardPendingStateShowsLeftArrow() {
        composeRule.setContent {
            MuxTvTheme {
                SeekHud(
                    state = SeekControllerState.Pending(
                        targetMs = 30_000L,
                        direction = -1,
                    ),
                    testTag = "player-seek-hud",
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-seek-hud")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-seek-hud").assertTextContains("←")
    }

    @Test
    fun idleStateHidesTheHud() {
        composeRule.setContent {
            MuxTvTheme {
                SeekHud(
                    state = SeekControllerState.Idle,
                    testTag = "player-seek-hud",
                )
            }
        }

        composeRule.onNodeWithTag("player-seek-hud").assertDoesNotExist()
    }

    @Test
    fun applyingAndCompletedStatesKeepTheTargetVisible() {
        composeRule.setContent {
            MuxTvTheme {
                SeekHud(
                    state = SeekControllerState.Completed(
                        targetMs = 3_700_000L,
                        direction = 1,
                    ),
                    testTag = "player-seek-hud",
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-seek-hud")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-seek-hud").assertTextContains("1:01:40")
    }
}

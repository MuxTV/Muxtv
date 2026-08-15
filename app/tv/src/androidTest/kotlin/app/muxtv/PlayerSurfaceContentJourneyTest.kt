package app.muxtv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.player.PlayerFavoriteAction
import app.muxtv.feature.player.PlayerSurfaceAction
import app.muxtv.feature.player.PlayerSurfaceContent
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import androidx.media3.session.MediaController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSurfaceContentJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun externalSurfaceHidesByDefaultAndGatesCapabilityDrivenActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerSurfaceHost(
                        connector = connector,
                        testTagPrefix = "external",
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("external-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("external-overlay").assertDoesNotExist()
            composeRule.onNodeWithTag("external-surface").assertIsFocused()

            composeRule.onNodeWithTag("external-surface").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("external-primary-action")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("external-primary-action").assertIsFocused()
            composeRule.onNodeWithTag("external-stop").assertExists()
            composeRule.onNodeWithTag("external-back").assertExists()
            composeRule.onNodeWithTag("external-favorite").assertDoesNotExist()
            composeRule.onNodeWithTag("external-audio").assertDoesNotExist()
            composeRule.onNodeWithTag("external-subtitle").assertDoesNotExist()
            composeRule.onNodeWithTag("external-timeline").assertDoesNotExist()
            composeRule.onNodeWithTag("external-timeline-time").assertDoesNotExist()
        } finally {
            connector.close()
        }
    }

    @Test
    fun catalogSurfaceShowsFavoriteWhenSupported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerSurfaceHost(
                        connector = connector,
                        testTagPrefix = "player",
                        favoriteSupported = true,
                        favoriteAction = PlayerFavoriteAction(
                            label = "☆ В избранное",
                            enabled = true,
                            onClick = { },
                        ),
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-surface").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-favorite").assertExists()
        } finally {
            connector.close()
        }
    }

    @Composable
    private fun PlayerSurfaceHost(
        connector: MuxTvMediaControllerConnector,
        testTagPrefix: String,
        favoriteSupported: Boolean = false,
        favoriteAction: PlayerFavoriteAction? = null,
    ) {
        var controller by remember { mutableStateOf<MediaController?>(null) }
        LaunchedEffect(connector) {
            controller = try {
                connector.awaitController(CONTROLLER_TIMEOUT_MILLIS)
            } catch (_: Exception) {
                null
            }
        }
        controller?.let { connected ->
            PlayerSurfaceContent(
                controller = connected,
                title = "External Stream",
                favoriteSupported = favoriteSupported,
                contentIdentity = connected,
                favoriteAction = favoriteAction,
                stopAction = PlayerSurfaceAction("Остановить") { },
                backAction = PlayerSurfaceAction("Назад") { },
                testTagPrefix = testTagPrefix,
            )
        }
    }

    private companion object {
        const val CONTROLLER_TIMEOUT_MILLIS = 20_000L
    }
}

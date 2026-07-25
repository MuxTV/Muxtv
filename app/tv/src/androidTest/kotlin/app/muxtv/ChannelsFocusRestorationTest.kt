package app.muxtv

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.channels.ChannelsRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class ChannelsFocusRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusedChannelIsRestoredAfterSaveAndRestore() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MuxTvTheme {
                ChannelsRoute(
                    playbackCatalog = StaticPlaybackCatalog,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }
        composeRule.waitForIdle()

        moveFocusToSecondChannel()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
    }

    @Test
    fun focusedChannelIsRestoredAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    val backFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(backFocusRequester) {
                        withFrameNanos { }
                        backFocusRequester.requestFocus()
                    }
                    MuxTvActionButton(
                        text = "Назад к каналам",
                        onClick = { playerOpen.value = false },
                        modifier = Modifier
                            .testTag("test-player-back")
                            .focusRequester(backFocusRequester),
                    )
                } else {
                    stateHolder.SaveableStateProvider("channels") {
                        ChannelsRoute(
                            playbackCatalog = StaticPlaybackCatalog,
                            profileId = "profile-main",
                            onOpenChannel = { playerOpen.value = true },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        moveFocusToSecondChannel()
        composeRule.onNodeWithTag("channel-row-1").pressEnter()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("test-player-back")
                .fetchSemanticsNodes()
                .size == 1
        }
        composeRule.onNodeWithTag("test-player-back")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("channel-row-1")
                .fetchSemanticsNodes()
                .size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
    }

    private fun moveFocusToSecondChannel() {
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressEnter() = performKeyInput {
    keyDown(Key.Enter)
    keyUp(Key.Enter)
}

private object StaticPlaybackCatalog : PlaybackCatalog {
    private val channels = listOf(
        channel(id = "channel-a", name = "Первый"),
        channel(id = "channel-b", name = "Второй"),
        channel(id = "channel-c", name = "Третий"),
    )

    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        flowOf(channels)

    override suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): PlayableChannel? = null

    override suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): ResolvedPlaybackRequest? = null

    private fun channel(
        id: String,
        name: String,
    ) = PlayableChannelSummary(
        channelId = id,
        displayName = name,
        logoUrl = null,
        groupTitle = "Тест",
        channelNumber = null,
        isFavorite = false,
        variantCount = 1,
    )
}

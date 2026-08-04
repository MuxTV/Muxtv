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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.channels.ChannelsRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
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
                    TestPlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("channels") {
                        ChannelsRoute(
                            playbackCatalog = StaticPlaybackCatalog,
                            epgGuideRepository = NoGuideEpgGuideRepository,
                            playbackSessionStateSource = NoPlaybackSessionStateSource,
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
        composeRule.waitUntilPlayerBack()
        composeRule.onNodeWithTag("test-player-back")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitUntilChannelRow(index = 1)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
    }

    @Test
    fun removedFocusedChannelFallsBackToNearestPreviousRowAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        val catalog = MutablePlaybackCatalog()
        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    TestPlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("channels") {
                        ChannelsRoute(
                            playbackCatalog = catalog,
                            epgGuideRepository = NoGuideEpgGuideRepository,
                            playbackSessionStateSource = NoPlaybackSessionStateSource,
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
        composeRule.waitUntilPlayerBack()

        catalog.remove("channel-b")
        composeRule.onNodeWithTag("test-player-back")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitUntilChannelRow(index = 0)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
    }

    @Test
    fun favoritesFilterKeepsFocusedFavoriteChannel() {
        val catalog = MutablePlaybackCatalog(
            testChannels.map { channel ->
                if (channel.channelId == "channel-b") channel.copy(isFavorite = true) else channel
            },
        )
        composeRule.setContent {
            MuxTvTheme {
                ChannelsRoute(
                    playbackCatalog = catalog,
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }
        composeRule.waitForIdle()

        moveFocusToSecondChannel()
        composeRule.onNodeWithTag("channels-filter-favorites").performClick()
        composeRule.waitUntilText("★  Второй")
        composeRule.waitForIdle()

        // The product contract is stable canonical-channel focus, not positional lazy-item
        // identity. On old Compose/TV runtimes a removed keyed item can briefly retain its old
        // index-derived test tag even after the surviving channel has been placed and focused.
        // Assert the actual surviving favorite semantics instead of coupling this contract to
        // `channel-row-0` after a 1 -> 0 reorder.
        composeRule.onNodeWithText("★  Второй", substring = false).assertIsFocused()
    }

    @Test
    fun favoritesFilterIsReachableAndOperableWithDpad() {
        val catalog = MutablePlaybackCatalog(
            testChannels.mapIndexed { index, channel ->
                if (index == 0) channel.copy(isFavorite = true) else channel
            },
        )
        composeRule.setContent {
            MuxTvTheme {
                ChannelsRoute(
                    playbackCatalog = catalog,
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }
        composeRule.waitForIdle()

        openFavoritesFromFirstRowWithDpad()
        composeRule.waitUntilText("★  Первый")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("★  Первый", substring = false).assertExists()
    }

    @Test
    fun emptyFavoritesRecoveryActionReceivesFocus() {
        composeRule.setContent {
            MuxTvTheme {
                ChannelsRoute(
                    playbackCatalog = MutablePlaybackCatalog(),
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }
        composeRule.waitForIdle()

        openFavoritesFromFirstRowWithDpad()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Показать все каналы").assertIsFocused()
    }

    @Test
    fun guideProjectionAppearsWithoutChangingInitialFocus() {
        composeRule.setContent {
            MuxTvTheme {
                ChannelsRoute(
                    playbackCatalog = StaticPlaybackCatalog,
                    epgGuideRepository = StaticNowNextEpgGuideRepository(
                        channelId = "channel-a",
                        currentTitle = "В эфире",
                        nextTitle = "Следом",
                    ),
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("Сейчас: В эфире").assertExists()
        composeRule.onNodeWithText("Далее: Следом").assertExists()
    }

    private fun moveFocusToSecondChannel() {
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
    }

    private fun openFavoritesFromFirstRowWithDpad() {
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        composeRule.onNodeWithTag("channels-filter-all").assertIsFocused()
        composeRule.onNodeWithTag("channels-filter-all").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("channels-filter-favorites").assertIsFocused()
        composeRule.onNodeWithTag("channels-filter-favorites").pressEnter()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilPlayerBack() {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("test-player-back").fetchSemanticsNodes().size == 1
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilChannelRow(index: Int) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("channel-row-$index").fetchSemanticsNodes().size == 1
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilText(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, substring = false).fetchSemanticsNodes().size == 1
        }
    }
}

@androidx.compose.runtime.Composable
private fun TestPlayer(onBack: () -> Unit) {
    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(backFocusRequester) {
        withFrameNanos { }
        backFocusRequester.requestFocus()
    }
    MuxTvActionButton(
        text = "Назад к каналам",
        onClick = onBack,
        modifier = Modifier
            .testTag("test-player-back")
            .focusRequester(backFocusRequester),
    )
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressEnter() = performKeyInput {
    keyDown(Key.Enter)
    keyUp(Key.Enter)
}

private val testChannels = listOf(
    testChannel(id = "channel-a", name = "Первый"),
    testChannel(id = "channel-b", name = "Второй"),
    testChannel(id = "channel-c", name = "Третий"),
)

private object StaticPlaybackCatalog : PlaybackCatalog {
    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        flowOf(testChannels.filterFor(query))

    override suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): PlayableChannel? = null

    override suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): PlaybackVariantResolution? = null

    override suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.NotFound

    override suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.NotFound
}

private class MutablePlaybackCatalog(
    initialChannels: List<PlayableChannelSummary> = testChannels,
) : PlaybackCatalog {
    private val channels = MutableStateFlow(initialChannels)

    fun remove(channelId: String) {
        channels.value = channels.value.filterNot { it.channelId == channelId }
    }

    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        channels.map { rows -> rows.filterFor(query) }

    override suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): PlayableChannel? = null

    override suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): PlaybackVariantResolution? = null

    override suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.NotFound

    override suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.NotFound
}

private fun List<PlayableChannelSummary>.filterFor(query: ChannelQuery): List<PlayableChannelSummary> =
    if (query.favoritesOnly) filter(PlayableChannelSummary::isFavorite) else this

private fun testChannel(
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

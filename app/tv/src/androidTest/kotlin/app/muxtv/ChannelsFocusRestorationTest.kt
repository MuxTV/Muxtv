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
import androidx.compose.ui.test.assertIsSelected
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
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
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
                channelsRoute()
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
                        channelsRoute(onOpenChannel = { playerOpen.value = true })
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
                        channelsRoute(
                            playbackCatalog = catalog,
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
                channelsRoute(playbackCatalog = catalog)
            }
        }
        composeRule.waitForIdle()

        moveFocusToSecondChannel()
        composeRule.onNodeWithTag("channels-filter-favorites").performClick()
        composeRule.waitUntilText("Второй")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("Второй", substring = false).assertExists()
        composeRule.onNodeWithTag("channels-filter-favorites").assertIsSelected()
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
                channelsRoute(playbackCatalog = catalog)
            }
        }
        composeRule.waitForIdle()

        openFavoritesFromFirstRowWithDpad()
        composeRule.waitUntilText("Первый")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("Первый", substring = false).assertExists()
        composeRule.onNodeWithTag("channels-filter-favorites").assertIsSelected()
    }

    @Test
    fun recentFilterIsReachableWithDpadAndUsesRecentOrder() {
        val recent = StaticRecentChannelsRepository(
            listOf(
                recentChannel(testChannels[2], 3_000L),
                recentChannel(testChannels[1], 2_000L),
            ),
        )
        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(recentChannelsRepository = recent)
            }
        }
        composeRule.waitForIdle()

        openRecentFromFirstRowWithDpad()
        composeRule.waitUntilText("Третий")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("Третий", substring = false).assertExists()
        composeRule.onNodeWithText("Показано недавних: 2").assertExists()
    }

    @Test
    fun firstRecentRowReturnsUpToRecentFilter() {
        val recent = StaticRecentChannelsRepository(
            listOf(recentChannel(testChannels[0], 1_000L)),
        )
        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(recentChannelsRepository = recent)
            }
        }
        composeRule.waitForIdle()

        openRecentFromFirstRowWithDpad()
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }

        composeRule.onNodeWithTag("channels-filter-recent").assertIsFocused()
    }

    @Test
    fun recentChannelFocusIsRestoredAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        val recent = StaticRecentChannelsRepository(
            listOf(
                recentChannel(testChannels[1], 2_000L),
                recentChannel(testChannels[2], 1_000L),
            ),
        )
        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    TestPlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("channels") {
                        channelsRoute(
                            recentChannelsRepository = recent,
                            onOpenChannel = { playerOpen.value = true },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        openRecentFromFirstRowWithDpad()
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused().pressEnter()
        composeRule.waitUntilPlayerBack()
        composeRule.onNodeWithTag("test-player-back")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitUntilText("Второй")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Второй", substring = false).assertIsFocused()
        composeRule.onNodeWithTag("channels-filter-recent").assertIsSelected()
    }

    @Test
    fun emptyFavoritesRecoveryActionReceivesFocus() {
        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(playbackCatalog = MutablePlaybackCatalog())
            }
        }
        composeRule.waitForIdle()

        openFavoritesFromFirstRowWithDpad()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Показать все каналы").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Показать все каналы").assertIsFocused()
    }

    @Test
    fun emptyRecentRecoveryActionReceivesFocus() {
        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(recentChannelsRepository = StaticRecentChannelsRepository(emptyList()))
            }
        }
        composeRule.waitForIdle()

        openRecentFromFirstRowWithDpad()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Показать все каналы").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Показать все каналы").assertIsFocused()
    }

    @Test
    fun guideProjectionAppearsWithoutChangingInitialFocus() {
        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(
                    epgGuideRepository = StaticNowNextEpgGuideRepository(
                        channelId = "channel-a",
                        currentTitle = "В эфире",
                        nextTitle = "Следом",
                    ),
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("Сейчас: В эфире", substring = true).assertExists()
        composeRule.onNodeWithText("Далее: Следом", substring = true).assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun channelsRoute(
        playbackCatalog: PlaybackCatalog = StaticPlaybackCatalog,
        recentChannelsRepository: RecentChannelsRepository = StaticRecentChannelsRepository(emptyList()),
        epgGuideRepository: app.muxtv.catalog.EpgGuideRepository = NoGuideEpgGuideRepository,
        onOpenChannel: (String) -> Unit = {},
    ) {
        ChannelsRoute(
            channelBrowseRepository = TestChannelBrowseRepository(
                playbackCatalog = playbackCatalog,
                recentChannelsRepository = recentChannelsRepository,
                epgGuideRepository = epgGuideRepository,
            ),
            epgGuideRepository = epgGuideRepository,
            playbackSessionStateSource = NoPlaybackSessionStateSource,
            profileId = "profile-main",
            onOpenChannel = onOpenChannel,
        )
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

    private fun openRecentFromFirstRowWithDpad() {
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
        composeRule.onNodeWithTag("channels-filter-favorites").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("channels-filter-recent").assertIsFocused()
        composeRule.onNodeWithTag("channels-filter-recent").pressEnter()
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

private class StaticRecentChannelsRepository(
    private val rows: List<RecentChannel>,
) : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> =
        flowOf(rows.take(query.limit))

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult = RecentChannelWriteResult.Applied
}

private fun recentChannel(
    channel: PlayableChannelSummary,
    timestamp: Long,
): RecentChannel = RecentChannel(
    channel = channel,
    lastSuccessfulPlaybackAtEpochMillis = timestamp,
)

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

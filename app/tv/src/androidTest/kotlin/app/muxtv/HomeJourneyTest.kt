package app.muxtv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.home.HOME_ADD_SOURCE_TEST_TAG
import app.muxtv.feature.home.HOME_HERO_TEST_TAG
import app.muxtv.feature.home.HomeRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyHomeOffersAddSourceWithDeterministicFocus() {
        var addSourceClicks = 0
        composeRule.setContent {
            MuxTvTheme {
                HomeRoute(
                    channelBrowseRepository = EmptyBrowseRepository,
                    recentChannelsRepository = NoRecentChannelsRepository,
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    hasSources = flowOf(false),
                    profileId = "profile-main",
                    onOpenChannel = {},
                    onOpenChannels = {},
                    onOpenGuide = {},
                    onAddSource = { addSourceClicks += 1 },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_ADD_SOURCE_TEST_TAG).assertIsFocused().pressEnter()
        composeRule.runOnIdle { check(addSourceClicks == 1) { "Expected one add-source click" } }
    }

    @Test
    fun heroLeadsToRailLeftAndPrimaryActionOpensPlayback() {
        var openedChannel: String? = null
        val railFocusRequester = FocusRequester()
        composeRule.setContent {
            MuxTvTheme {
                Row {
                    Box(
                        modifier = Modifier
                            .testTag("test-rail-target")
                            .focusRequester(railFocusRequester)
                            .focusable(),
                    )
                    HomeRoute(
                        channelBrowseRepository = EmptyBrowseRepository,
                        recentChannelsRepository = StaticRecentChannelsFixture(),
                        epgGuideRepository = NoGuideEpgGuideRepository,
                        playbackSessionStateSource = NoPlaybackSessionStateSource,
                        hasSources = flowOf(true),
                        profileId = "profile-main",
                        onOpenChannel = { openedChannel = it },
                        onOpenChannels = {},
                        onOpenGuide = {},
                        onAddSource = {},
                        railFocusRequester = railFocusRequester,
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(HOME_HERO_TEST_TAG).fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_HERO_TEST_TAG).assertIsFocused().press(Key.DirectionLeft)
        composeRule.onNodeWithTag("test-rail-target").assertIsFocused()
        composeRule.onNodeWithTag("test-rail-target").press(Key.DirectionRight)
        composeRule.onNodeWithTag(HOME_HERO_TEST_TAG).assertIsFocused().pressEnter()
        composeRule.runOnIdle { check(openedChannel == "channel-home-1") }
    }

    @Test
    fun heroDownEntersFirstRailAndCardOkOpensChannel() {
        var openedChannel: String? = null
        composeRule.setContent {
            MuxTvTheme {
                HomeRoute(
                    channelBrowseRepository = FavoritesBrowseRepository,
                    recentChannelsRepository = NoRecentChannelsRepository,
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    hasSources = flowOf(true),
                    profileId = "profile-main",
                    onOpenChannel = { openedChannel = it },
                    onOpenChannels = {},
                    onOpenGuide = {},
                    onAddSource = {},
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("home-card-favorites-0").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_HERO_TEST_TAG).assertIsFocused().press(Key.DirectionDown)
        composeRule.onNodeWithTag("home-card-favorites-0")
            .assertIsFocused()
            .press(Key.DirectionUp)
        composeRule.onNodeWithTag(HOME_HERO_TEST_TAG).assertIsFocused().press(Key.DirectionDown)
        composeRule.onNodeWithTag("home-card-favorites-0").assertIsFocused().pressEnter()
        composeRule.runOnIdle { check(openedChannel == "channel-favorite-1") }
    }

    @Test
    fun lastActiveRailCardIsRestoredAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    TestHomePlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("home") {
                        HomeRoute(
                            channelBrowseRepository = FavoritesBrowseRepository,
                            recentChannelsRepository = StaticRecentChannelsFixture(),
                            epgGuideRepository = NoGuideEpgGuideRepository,
                            playbackSessionStateSource = NoPlaybackSessionStateSource,
                            hasSources = flowOf(true),
                            profileId = "profile-main",
                            onOpenChannel = { playerOpen.value = true },
                            onOpenChannels = {},
                            onOpenGuide = {},
                            onAddSource = {},
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("home-card-favorites-0").fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithTag("home-card-recent-0").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        // Give both rails historical anchors, then make Favorites the single active owner.
        // Independent per-rail restoration is invalid because both rails can then race on return.
        composeRule.onNodeWithTag("home-card-recent-0")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag("home-card-recent-0").assertIsFocused()
        composeRule.onNodeWithTag("home-card-favorites-0")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag("home-card-favorites-0").assertIsFocused().pressEnter()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TEST_HOME_PLAYER_BACK_TAG).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag(TEST_HOME_PLAYER_BACK_TAG).assertIsFocused().pressEnter()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("home-card-favorites-0").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home-card-favorites-0").assertIsFocused()
    }

    @Test
    fun heroAndRailsRenderForScreenshotEvidence() {
        composeRule.setContent {
            MuxTvTheme {
                HomeRoute(
                    channelBrowseRepository = FavoritesBrowseRepository,
                    recentChannelsRepository = StaticRecentChannelsFixture(),
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    playbackSessionStateSource = NoPlaybackSessionStateSource,
                    hasSources = flowOf(true),
                    profileId = "profile-main",
                    onOpenChannel = {},
                    onOpenChannels = {},
                    onOpenGuide = {},
                    onAddSource = {},
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("home-favorites-header").fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithTag("home-recent-header").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("home-card-favorites-0")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("home-hero-rails")
    }

    private companion object {
        val EmptyBrowseRepository = object : ChannelBrowseRepository {
            override fun pages(query: ChannelBrowseQuery): Flow<androidx.paging.PagingData<ChannelBrowseItem>> =
                flowOf(androidx.paging.PagingData.empty())

            override fun managementPages(query: app.muxtv.catalog.ChannelManagementQuery):
                Flow<androidx.paging.PagingData<app.muxtv.catalog.ChannelManagementItem>> =
                flowOf(androidx.paging.PagingData.empty())
        }

        val FavoritesBrowseRepository = object : ChannelBrowseRepository {
            override fun pages(query: ChannelBrowseQuery): Flow<androidx.paging.PagingData<ChannelBrowseItem>> =
                flowOf(
                    androidx.paging.PagingData.from(
                        listOf(
                            ChannelBrowseItem(
                                channelId = "channel-favorite-1",
                                displayName = "Первый канал",
                                channelNumber = "1",
                                groupTitle = "Общие",
                                isFavorite = true,
                                isCurrentPlayback = false,
                                currentProgrammeTitle = "Новости",
                                currentProgrammeEndEpochMillis = null,
                                nextProgrammeTitle = null,
                                nextProgrammeStartEpochMillis = null,
                                variantCount = 1,
                                guideState = GuideProjectionState.READY,
                            ),
                        ),
                    ),
                )

            override fun managementPages(query: app.muxtv.catalog.ChannelManagementQuery):
                Flow<androidx.paging.PagingData<app.muxtv.catalog.ChannelManagementItem>> =
                flowOf(androidx.paging.PagingData.empty())
        }
    }
}

@Composable
private fun TestHomePlayer(onBack: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    MuxTvActionButton(
        text = "Назад",
        onClick = onBack,
        modifier = Modifier
            .testTag(TEST_HOME_PLAYER_BACK_TAG)
            .focusRequester(requester),
    )
}

internal class StaticRecentChannelsFixture : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> =
        flowOf(
            listOf(
                RecentChannel(
                    channel = PlayableChannelSummary(
                        channelId = "channel-home-1",
                        displayName = "Домашний канал",
                        logoUrl = null,
                        groupTitle = "Общие",
                        channelNumber = "7",
                        isFavorite = true,
                        variantCount = 1,
                    ),
                    lastSuccessfulPlaybackAtEpochMillis = 100L,
                ),
            ),
        )

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult = RecentChannelWriteResult.ProfileUnavailable
}

private fun SemanticsNodeInteraction.pressEnter(): SemanticsNodeInteraction = apply {
    performKeyInput {
        keyDown(Key.Enter)
        keyUp(Key.Enter)
    }
}

private fun SemanticsNodeInteraction.press(
    key: Key,
): SemanticsNodeInteraction = apply {
    performKeyInput {
        keyDown(key)
        keyUp(key)
    }
}

private const val TEST_HOME_PLAYER_BACK_TAG = "test-home-player-back"

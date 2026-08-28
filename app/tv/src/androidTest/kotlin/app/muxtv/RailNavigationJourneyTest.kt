package app.muxtv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceManagement
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePlaybackApprovalResetResult
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.catalog.SourceRefreshOverview
import app.muxtv.catalog.SourceRefreshPolicy
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class RailNavigationJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transientRailLabelsFollowFocusWithoutContentReflow() {
        withNavigation { _ ->
            val hero = composeRule.onNodeWithTag("home-hero")
            hero.assertIsFocused()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("home-card-favorites-0")
                    .fetchSemanticsNodes()
                    .size == 1
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Главная").fetchSemanticsNodes().isEmpty()
            }
            val initialBounds = hero.fetchSemanticsNode().boundsInRoot

            hero.press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-home").assertIsFocused()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Главная").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            check(hero.fetchSemanticsNode().boundsInRoot == initialBounds) {
                "Home hero bounds changed while the rail expanded over stable content"
            }
            composeRule.captureScreenshot("home-rail-expanded")

            composeRule.onNodeWithTag("nav-home").press(Key.DirectionRight)
            hero.assertIsFocused()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Главная").fetchSemanticsNodes().isEmpty()
            }
            composeRule.waitForIdle()
            check(hero.fetchSemanticsNode().boundsInRoot == initialBounds) {
                "Home hero bounds changed after rail focus returned to content"
            }
            composeRule.captureScreenshot("home-rail-collapsed")
        }
    }

    @Test
    fun contentFocusLeftEntersRailAndRightReturnsWithRestoration() {
        withNavigation { _ ->
            composeRule.onNodeWithTag("home-hero").assertIsFocused().press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-home").assertIsFocused()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Главная").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("nav-home").press(Key.DirectionDown)
            composeRule.onNodeWithTag("nav-channels").assertIsFocused()
            composeRule.onNodeWithTag("nav-channels").press(Key.DirectionRight)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("home-hero").assertIsFocused()
        }
    }

    @Test
    fun backFromExpandedRailReturnsToOriginatingContentBeforeRouteNavigation() {
        withNavigation { _ ->
            composeRule.onNodeWithTag("home-hero").assertIsFocused().press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-home").assertIsFocused()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Главная").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }

            composeRule.onNodeWithTag("home-hero").assertIsFocused()
        }
    }

    @Test
    fun railSelectsDestinationsWithDpad() {
        withNavigation { _ ->
            composeRule.onNodeWithTag("home-hero").press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-home").assertIsFocused().press(Key.DirectionDown)
            composeRule.onNodeWithTag("nav-channels").assertIsFocused().press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        }
    }

    @Test
    fun channelsFilterFocusGraphIsBidirectionalAndOnlyAllLeftEntersRail() {
        withNavigation { _ ->
            composeRule.onNodeWithTag("home-hero").press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-home").press(Key.DirectionDown)
            composeRule.onNodeWithTag("nav-channels").press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
            }

            composeRule.onNodeWithTag("channel-row-0").assertIsFocused().press(Key.DirectionUp)
            composeRule.onNodeWithTag("channels-filter-all").assertIsFocused()
                .press(Key.DirectionRight)
            composeRule.onNodeWithTag("channels-filter-favorites").assertIsFocused()
                .press(Key.DirectionRight)
            composeRule.onNodeWithTag("channels-filter-recent").assertIsFocused()
                .press(Key.DirectionLeft)
            composeRule.onNodeWithTag("channels-filter-favorites").assertIsFocused()
                .press(Key.DirectionLeft)
            composeRule.onNodeWithTag("channels-filter-all").assertIsFocused()
                .press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-channels").assertIsFocused()
        }
    }

    private fun withNavigation(block: (MuxTvMediaControllerConnector) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controllerConnector = MuxTvMediaControllerConnector(context)
        try {
            composeRule.setContent {
                MuxTvTheme {
                    AppNavigation(
                        playbackCatalog = RailPlaybackCatalogFixture,
                        channelBrowseRepository = TestChannelBrowseRepository(
                            RailPlaybackCatalogFixture,
                            RailRecentChannelsFixture,
                            RailEpgGuideFixture,
                        ),
                        channelPreferencesRepository = NoChannelPreferencesRepository,
                        channelSearchRepository = NoChannelSearchRepository,
                        guideWindowRepository = TestGuideWindowRepository,
                        recentChannelsRepository = RailRecentChannelsFixture,
                        epgGuideRepository = RailEpgGuideFixture,
                        controllerConnector = controllerConnector,
                        sourceManagement = RailSourceManagementFixture,
                        sourceOnboarding = RailOnboardingFixture,
                    )
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().size == 1
            }
            composeRule.waitForIdle()
            block(controllerConnector)
        } finally {
            controllerConnector.close()
        }
    }
}

private object RailPlaybackCatalogFixture : app.muxtv.catalog.PlaybackCatalog {
    override fun observeChannels(query: app.muxtv.catalog.ChannelQuery):
        kotlinx.coroutines.flow.Flow<List<app.muxtv.catalog.PlayableChannelSummary>> =
        flowOf(RAIL_CHANNELS.take(query.limit))

    override suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): app.muxtv.catalog.PlayableChannel? = null

    override suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): app.muxtv.catalog.PlaybackVariantResolution? = null

    override suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): app.muxtv.catalog.PlaybackAccessMutationResult =
        app.muxtv.catalog.PlaybackAccessMutationResult.NotFound

    override suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): app.muxtv.catalog.PlaybackAccessMutationResult =
        app.muxtv.catalog.PlaybackAccessMutationResult.NotFound
}

private val RAIL_CHANNELS = listOf(
    PlayableChannelSummary(
        channelId = "channel-rail-1",
        displayName = "Первый канал",
        logoUrl = null,
        groupTitle = "Общие",
        channelNumber = "1",
        isFavorite = true,
        variantCount = 1,
    ),
    PlayableChannelSummary(
        channelId = "channel-rail-2",
        displayName = "Россия 1",
        logoUrl = null,
        groupTitle = "Общие",
        channelNumber = "2",
        isFavorite = true,
        variantCount = 1,
    ),
    PlayableChannelSummary(
        channelId = "channel-rail-3",
        displayName = "НТВ",
        logoUrl = null,
        groupTitle = "Общие",
        channelNumber = "3",
        isFavorite = true,
        variantCount = 1,
    ),
    PlayableChannelSummary(
        channelId = "channel-rail-4",
        displayName = "Культура",
        logoUrl = null,
        groupTitle = "Общие",
        channelNumber = "4",
        isFavorite = true,
        variantCount = 1,
    ),
    PlayableChannelSummary(
        channelId = "channel-rail-5",
        displayName = "Матч ТВ",
        logoUrl = null,
        groupTitle = "Спорт",
        channelNumber = "5",
        isFavorite = true,
        variantCount = 1,
    ),
    PlayableChannelSummary(
        channelId = "channel-rail-6",
        displayName = "Пятый канал",
        logoUrl = null,
        groupTitle = "Общие",
        channelNumber = "6",
        isFavorite = true,
        variantCount = 1,
    ),
)

private object RailRecentChannelsFixture : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> =
        flowOf(
            RAIL_CHANNELS.take(query.limit).mapIndexed { index, channel ->
                RecentChannel(
                    channel = channel,
                    lastSuccessfulPlaybackAtEpochMillis = 6_000L - index,
                )
            },
        )

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): app.muxtv.catalog.RecentChannelWriteResult =
        app.muxtv.catalog.RecentChannelWriteResult.ProfileUnavailable
}

private object RailEpgGuideFixture : EpgGuideRepository {
    override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> =
        query.canonicalChannelIds.map { channelId ->
            val currentStart = (query.nowEpochMillis - 30 * 60_000L).coerceAtLeast(0L)
            val currentEnd = currentStart + 60 * 60_000L
            val (currentTitle, nextTitle) = when (channelId) {
                "channel-rail-1" -> "Вести" to "Вечер с Владимиром Соловьёвым"
                "channel-rail-2" -> "Новости региона" to "Утро России"
                "channel-rail-3" -> "Сегодня" to "Квартирный вопрос"
                "channel-rail-4" -> "Культурная среда" to "Большая опера"
                "channel-rail-5" -> "Матч дня" to "Футбол России"
                else -> "Пятый элемент" to "Детективный клуб"
            }
            ChannelNowNext(
                canonicalChannelId = channelId,
                state = GuideProjectionState.READY,
                current = GuideProgramme(
                    startEpochMillis = currentStart,
                    endEpochMillis = currentEnd,
                    title = currentTitle,
                ),
                next = GuideProgramme(
                    startEpochMillis = currentEnd,
                    endEpochMillis = currentEnd + 30 * 60_000L,
                    title = nextTitle,
                ),
                nextBoundaryEpochMillis = currentEnd,
            )
        }

    override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
}

private object RailSourceManagementFixture : SourceManagement {
    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> = flowOf(
        listOf(
            SourceRefreshOverview(
                sourceId = "source-rail",
                sourceName = "Домашний IPTV",
                hasStoredAccess = true,
                activeRevision = 1,
                policy = null,
                status = null,
            ),
        ),
    )

    override fun refreshNow(sourceId: String) = Unit
    override suspend fun updatePolicy(policy: SourceRefreshPolicy) = Unit
    override suspend fun removePolicy(sourceId: String) = Unit
    override suspend fun revokePlaybackApprovals(
        sourceId: String,
    ): SourcePlaybackApprovalResetResult = SourcePlaybackApprovalResetResult.SourceNotFound
}

private object RailOnboardingFixture : SourceOnboarding {
    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = error("Source entry is not part of this journey")

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult = SourceActivationResult.Failed(
        SourceActivationFailure.Unexpected,
        cleanupPending = false,
    )

    override suspend fun cancel(
        handle: SourcePreparationHandle,
    ): SourceCancellationResult = SourceCancellationResult.NotFound

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
}

private fun SemanticsNodeInteraction.press(
    key: Key,
    count: Int = 1,
): SemanticsNodeInteraction = apply {
    performKeyInput {
        repeat(count) {
            keyDown(key)
            keyUp(key)
        }
    }
}

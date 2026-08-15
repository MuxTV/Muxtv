package app.muxtv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.test.core.app.ApplicationProvider
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import org.junit.Rule
import org.junit.Test

class RailNavigationJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Главная").fetchSemanticsNodes().isEmpty()
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
                            NoRecentChannelsRepository,
                            NoGuideEpgGuideRepository,
                        ),
                        channelPreferencesRepository = NoChannelPreferencesRepository,
                        channelSearchRepository = NoChannelSearchRepository,
                        guideWindowRepository = TestGuideWindowRepository,
                        recentChannelsRepository = NoRecentChannelsRepository,
                        epgGuideRepository = NoGuideEpgGuideRepository,
                        controllerConnector = controllerConnector,
                        sourceRefreshStore = RailSourceStoreFixture,
                        sourceRefreshScheduler = SourceRefreshScheduler(context, RailSourceStoreFixture),
                        sourceEntryOnboarding = RailOnboardingFixture,
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
        kotlinx.coroutines.flow.flowOf(
            listOf(
                app.muxtv.catalog.PlayableChannelSummary(
                    channelId = "channel-rail",
                    displayName = "Первый канал",
                    logoUrl = null,
                    groupTitle = "Общие",
                    channelNumber = "1",
                    isFavorite = false,
                    variantCount = 1,
                ),
            ),
        )

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

private object RailSourceStoreFixture : app.muxtv.database.SourceRefreshStore {
    override suspend fun getTarget(sourceId: String): app.muxtv.database.SourceRefreshTarget? = null

    override fun observeOverviews():
        kotlinx.coroutines.flow.Flow<List<app.muxtv.database.SourceRefreshOverview>> =
        kotlinx.coroutines.flow.flowOf(
            listOf(
                app.muxtv.database.SourceRefreshOverview(
                    sourceId = "source-rail",
                    sourceName = "Домашний IPTV",
                    hasCredentialReference = true,
                    activeRevision = 1,
                    policy = null,
                    status = null,
                ),
            ),
        )

    override suspend fun getPolicies(): List<app.muxtv.database.SourceRefreshPolicy> = emptyList()

    override suspend fun upsertPolicy(policy: app.muxtv.database.SourceRefreshPolicy) = Unit

    override suspend fun removePolicy(sourceId: String) = Unit

    override fun observeStatus(sourceId: String):
        kotlinx.coroutines.flow.Flow<app.muxtv.database.SourceRefreshStatus?> =
        kotlinx.coroutines.flow.flowOf(null)

    override suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<app.muxtv.database.SourceRefreshAttempt> = emptyList()

    override suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean = false

    override suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: app.muxtv.database.SourceRefreshTrigger,
        completion: app.muxtv.database.SourceRefreshCompletion,
        expectedCredentialRef: String?,
    ) = Unit
}

private object RailOnboardingFixture : SourceEntryOnboarding {
    override suspend fun prepare(
        input: app.muxtv.catalog.refresh.RemoteSourceOnboardingInput,
    ): app.muxtv.catalog.refresh.RemoteSourcePreparationResult =
        error("Source entry is not part of this journey")

    override suspend fun activate(
        token: app.muxtv.catalog.refresh.RemoteSourcePreparationToken,
        sourceName: String,
    ): app.muxtv.catalog.refresh.RemoteSourceActivationResult =
        error("Source entry is not part of this journey")

    override suspend fun cancel(
        token: app.muxtv.catalog.refresh.RemoteSourcePreparationToken,
    ): app.muxtv.catalog.refresh.RemoteSourceCancellationResult =
        error("Source entry is not part of this journey")

    override suspend fun restoreLatestPrepared(): app.muxtv.catalog.refresh.RemoteSourcePreparationResult.Prepared? =
        null
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

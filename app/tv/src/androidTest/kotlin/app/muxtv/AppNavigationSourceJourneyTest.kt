package app.muxtv

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.SourceRefreshAttempt
import app.muxtv.database.SourceRefreshCompletion
import app.muxtv.database.SourceRefreshOverview
import app.muxtv.database.SourceRefreshPolicy
import app.muxtv.database.SourceRefreshStatus
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRefreshTarget
import app.muxtv.database.SourceRefreshTrigger
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class AppNavigationSourceJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun httpsSourceCanBeAddedAndAppearsInSourcesAndChannelsWithoutTouch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceStore = JourneySourceRefreshStore()
        val playbackCatalog = JourneyPlaybackCatalog()
        val secretLocator = "https://provider.example/list.m3u?token=journey-secret"
        val onboarding = JourneySourceEntryOnboarding(
            sourceStore = sourceStore,
            playbackCatalog = playbackCatalog,
            expectedLocator = secretLocator,
        )
        val scheduler = SourceRefreshScheduler(context, sourceStore)
        val controllerConnector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    AppNavigation(
                        playbackCatalog = playbackCatalog,
                        channelPreferencesRepository = NoChannelPreferencesRepository,
                        channelSearchRepository = NoChannelSearchRepository,
                        recentChannelsRepository = NoRecentChannelsRepository,
                        epgGuideRepository = NoGuideEpgGuideRepository,
                        controllerConnector = controllerConnector,
                        sourceRefreshStore = sourceStore,
                        sourceRefreshScheduler = scheduler,
                        sourceEntryOnboarding = onboarding,
                    )
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("nav-home").assertIsFocused().press(Key.DirectionRight, 4)
            composeRule.onNodeWithTag("nav-sources").assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("sources-add").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("sources-add").assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-name").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("source-name").assertIsFocused()
            composeRule.onNodeWithTag("source-name").performTextInput("Домашний IPTV")
            composeRule.onNodeWithTag("source-name").press(Key.DirectionDown)

            composeRule.onNodeWithTag("source-locator").assertIsFocused()
            composeRule.onNodeWithTag("source-locator")
                .performSemanticsAction(SemanticsActions.SetText) { action ->
                    action(AnnotatedString(secretLocator))
                }
            composeRule.onNodeWithTag("source-locator").press(Key.DirectionDown)
            composeRule.onNodeWithText("Показать временно")
                .assertIsFocused()
                .press(Key.DirectionRight)
            composeRule.onNodeWithText("Проверить").assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-confirm").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithText("Подтвердите адрес: https://provider.example")
                .assertExists()
            composeRule.onAllNodes(
                matcher = hasText(secretLocator, substring = true),
                useUnmergedTree = true,
            ).assertCountEquals(0)
            composeRule.onNodeWithTag("source-confirm").assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("sources-add").fetchSemanticsNodes().size == 1 &&
                    composeRule.onAllNodes(hasText("Домашний IPTV", substring = false))
                        .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Домашний IPTV").assertExists()
            composeRule.onNodeWithTag("sources-add")
                .assertIsFocused()
                .press(Key.DirectionUp)
            composeRule.onNodeWithTag("nav-sources")
                .assertIsFocused()
                .press(Key.DirectionLeft, 3)
            composeRule.onNodeWithTag("nav-channels").assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
            composeRule.onNodeWithText("1  Первый канал", substring = false).assertExists()
        } finally {
            controllerConnector.close()
        }
    }
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

private object NoRecentChannelsRepository : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> = flowOf(emptyList())

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult = RecentChannelWriteResult.ProfileUnavailable
}

private class JourneySourceEntryOnboarding(
    private val sourceStore: JourneySourceRefreshStore,
    private val playbackCatalog: JourneyPlaybackCatalog,
    private val expectedLocator: String,
) : SourceEntryOnboarding {
    private val token = RemoteSourcePreparationToken.parse(
        "00000000-0000-4000-8000-000000000101",
    )

    override suspend fun prepare(
        input: RemoteSourceOnboardingInput,
    ): RemoteSourcePreparationResult {
        check(input.locator == expectedLocator)
        check(!input.insecureHttpApproved)
        return RemoteSourcePreparationResult.Prepared(
            token = token,
            scheme = "https",
            host = "provider.example",
        )
    }

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult {
        check(token == this.token)
        sourceStore.publish(sourceName)
        playbackCatalog.publish(sourceName)
        return RemoteSourceActivationResult.Activated(
            sourceId = JOURNEY_SOURCE_ID,
            revisionNumber = 1,
            previousRevisionNumber = 0,
            entryCount = 1,
            skippedEntries = 0,
            warningCount = 0,
        )
    }

    override suspend fun cancel(
        token: RemoteSourcePreparationToken,
    ): RemoteSourceCancellationResult = RemoteSourceCancellationResult.NotFound

    override suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared? = null
}

private class JourneySourceRefreshStore : SourceRefreshStore {
    private val overviews = MutableStateFlow<List<SourceRefreshOverview>>(emptyList())

    fun publish(sourceName: String) {
        overviews.value = listOf(
            SourceRefreshOverview(
                sourceId = JOURNEY_SOURCE_ID,
                sourceName = sourceName,
                hasCredentialReference = true,
                activeRevision = 1,
                policy = null,
                status = null,
            ),
        )
    }

    override suspend fun getTarget(sourceId: String): SourceRefreshTarget? = null

    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> = overviews

    override suspend fun getPolicies(): List<SourceRefreshPolicy> = emptyList()

    override suspend fun upsertPolicy(policy: SourceRefreshPolicy) = Unit

    override suspend fun removePolicy(sourceId: String) = Unit

    override fun observeStatus(sourceId: String): Flow<SourceRefreshStatus?> = flowOf(null)

    override suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<SourceRefreshAttempt> = emptyList()

    override suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean = false

    override suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: SourceRefreshTrigger,
        completion: SourceRefreshCompletion,
        expectedCredentialRef: String?,
    ) = Unit
}

private class JourneyPlaybackCatalog : PlaybackCatalog {
    private val channels = MutableStateFlow<List<PlayableChannelSummary>>(emptyList())
    private var sourceName: String = "Домашний IPTV"

    fun publish(sourceName: String) {
        this.sourceName = sourceName
        channels.value = listOf(channelSummary())
    }

    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> = channels

    override suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): PlayableChannel? = if (channelId == JOURNEY_CHANNEL_ID) {
        PlayableChannel(
            summary = channelSummary(),
            variants = listOf(
                PlayableVariant(
                    variantId = "variant-journey",
                    sourceId = JOURNEY_SOURCE_ID,
                    sourceName = sourceName,
                    locator = "https://stream.example/live.m3u8",
                    userAgent = null,
                    referrer = null,
                ),
            ),
        )
    } else {
        null
    }

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

    private fun channelSummary() = PlayableChannelSummary(
        channelId = JOURNEY_CHANNEL_ID,
        displayName = "Первый канал",
        logoUrl = null,
        groupTitle = "Общие",
        channelNumber = "1",
        isFavorite = false,
        variantCount = 1,
    )
}

private const val JOURNEY_SOURCE_ID = "source-journey"
private const val JOURNEY_CHANNEL_ID = "channel-journey"

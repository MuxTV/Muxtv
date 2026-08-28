package app.muxtv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import app.muxtv.feature.player.PlaybackStartGateway
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class AppNavigationSourceJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun httpsSourceCanBeAddedAndAppearsInSourcesAndChannelsWithoutTouch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceManagement = JourneySourceManagement()
        val playbackCatalog = JourneyPlaybackCatalog()
        val secretLocator = "https://provider.example/list.m3u?token=journey-secret"
        val onboarding = JourneySourceEntryOnboarding(
            sourceManagement = sourceManagement,
            playbackCatalog = playbackCatalog,
            expectedLocator = secretLocator,
        )
        val controllerConnector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    AppNavigation(
                        playbackCatalog = playbackCatalog,
                        channelBrowseRepository = TestChannelBrowseRepository(
                            playbackCatalog,
                            NoRecentChannelsRepository,
                            NoGuideEpgGuideRepository,
                        ),
                        channelPreferencesRepository = NoChannelPreferencesRepository,
                        channelSearchRepository = NoChannelSearchRepository,
                        guideWindowRepository = TestGuideWindowRepository,
                        recentChannelsRepository = NoRecentChannelsRepository,
                        epgGuideRepository = NoGuideEpgGuideRepository,
                        controllerConnector = controllerConnector,
                        sourceManagement = sourceManagement,
                        sourceOnboarding = onboarding,
                    )
                }
            }
            composeRule.waitForIdle()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("home-add-source").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("home-add-source").assertIsFocused().press(Key.Enter)

            // Home's CTA owns the direct AddSource journey; there is no intermediate Sources list.
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

            // Activation pops the direct AddSource entry back to Home.
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("home-hero")
                .assertIsFocused()
                .press(Key.DirectionLeft)

            // Verify the activated source through Settings -> Sources.
            composeRule.onNodeWithTag("nav-home")
                .assertIsFocused()
                .press(Key.DirectionDown, count = 4)
            composeRule.onNodeWithTag("nav-settings").assertIsFocused().press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("settings-section-sources")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("settings-section-sources")
                .assertIsFocused()
                .press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("sources-add").fetchSemanticsNodes().size == 1 &&
                    composeRule.onAllNodes(hasText("Домашний IPTV", substring = false))
                        .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Домашний IPTV").assertExists()

            // Return to Settings, enter its selected rail item, then move to Channels.
            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("settings-section-sources")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("settings-section-sources")
                .assertIsFocused()
                .press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-settings")
                .assertIsFocused()
                .press(Key.DirectionUp, count = 3)
            composeRule.onNodeWithTag("nav-channels").assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
            composeRule.onNodeWithText("Первый канал", substring = false).assertExists()
        } finally {
            controllerConnector.close()
        }
    }

    @Test
    fun doctorBackAfterPlaybackRejectionReturnsToChannelsWithoutRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceManagement = JourneySourceManagement().apply { publish("Домашний IPTV") }
        val playbackCatalog = JourneyPlaybackCatalog().apply { publish("Домашний IPTV") }
        val controllerConnector = MuxTvMediaControllerConnector(context)
        var playbackStartCount = 0

        try {
            composeRule.setContent {
                MuxTvTheme {
                    AppNavigation(
                        playbackCatalog = playbackCatalog,
                        channelBrowseRepository = TestChannelBrowseRepository(
                            playbackCatalog,
                            NoRecentChannelsRepository,
                            NoGuideEpgGuideRepository,
                        ),
                        channelPreferencesRepository = NoChannelPreferencesRepository,
                        channelSearchRepository = NoChannelSearchRepository,
                        guideWindowRepository = TestGuideWindowRepository,
                        recentChannelsRepository = NoRecentChannelsRepository,
                        epgGuideRepository = NoGuideEpgGuideRepository,
                        controllerConnector = controllerConnector,
                        sourceManagement = sourceManagement,
                        sourceOnboarding = UnusedSourceEntryOnboarding,
                        playbackObservationReader = PlaybackObservationReader {
                            listOf(
                                PlaybackObservation(
                                    kind = PlaybackObservationKind.RECOVERY_FAILED,
                                    failureCategory = PlaybackFailureCategory.TIMEOUT,
                                    attemptNumber = 3,
                                    attemptLimit = 3,
                                    timestampEpochMillis = 1L,
                                ),
                            )
                        },
                        playbackStartGateway = PlaybackStartGateway { _, _, _ ->
                            playbackStartCount += 1
                            PlaybackStartResult.Rejected(
                                reason = PlaybackStartFailure.RecoveryExhausted,
                                observationAvailable = true,
                            )
                        },
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("home-hero")
                .assertIsFocused()
                .press(Key.DirectionLeft)
            composeRule.onNodeWithTag("nav-home")
                .assertIsFocused()
                .press(Key.DirectionDown)
            composeRule.onNodeWithTag("nav-channels").assertIsFocused().press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("channel-row-0").assertIsFocused().press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-doctor").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-doctor").assertIsFocused().press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("doctor-export").fetchSemanticsNodes().size == 1
            }

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
            }
            composeRule.runOnIdle { check(playbackStartCount == 1) }
        } finally {
            controllerConnector.close()
        }
    }
}

private object UnusedSourceEntryOnboarding : SourceOnboarding {
    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = error("Source entry is not part of this journey")

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult = SourceActivationResult.Failed(
        reason = SourceActivationFailure.Unexpected,
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

internal object NoRecentChannelsRepository : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> = flowOf(emptyList())

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult = RecentChannelWriteResult.ProfileUnavailable
}

private class JourneySourceEntryOnboarding(
    private val sourceManagement: JourneySourceManagement,
    private val playbackCatalog: JourneyPlaybackCatalog,
    private val expectedLocator: String,
) : SourceOnboarding {
    private val handle = JourneyPreparationHandle()

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult {
        check(locator == expectedLocator)
        check(!insecureHttpApproved)
        return SourcePreparationResult.Prepared(
            handle = handle,
            displayEndpoint = "https://provider.example",
        )
    }

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult {
        check(handle === this.handle)
        sourceManagement.publish(sourceName)
        playbackCatalog.publish(sourceName)
        return SourceActivationResult.Activated
    }

    override suspend fun cancel(
        handle: SourcePreparationHandle,
    ): SourceCancellationResult = SourceCancellationResult.NotFound

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
}

private class JourneyPreparationHandle : SourcePreparationHandle()

private class JourneySourceManagement : SourceManagement {
    private val overviews = MutableStateFlow<List<SourceRefreshOverview>>(emptyList())

    fun publish(sourceName: String) {
        overviews.value = listOf(
            SourceRefreshOverview(
                sourceId = JOURNEY_SOURCE_ID,
                sourceName = sourceName,
                hasStoredAccess = true,
                activeRevision = 1,
                policy = null,
                status = null,
            ),
        )
    }

    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> = overviews
    override fun refreshNow(sourceId: String) = Unit
    override suspend fun updatePolicy(policy: SourceRefreshPolicy) = Unit
    override suspend fun removePolicy(sourceId: String) = Unit
    override suspend fun revokePlaybackApprovals(
        sourceId: String,
    ): SourcePlaybackApprovalResetResult = SourcePlaybackApprovalResetResult.SourceNotFound
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

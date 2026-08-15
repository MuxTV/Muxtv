package app.muxtv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
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
import app.muxtv.feature.sources.SourcePlaybackApprovalActions
import app.muxtv.feature.settings.SETTINGS_SOURCES_TEST_TAG
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class SettingsJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsSectionsOpenSourcesAndBackRestoresSectionFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceStore = StaticSourceRefreshStore().apply { publish("Домашний IPTV") }
        val playbackCatalog = StaticPlaybackCatalogFixture
        val scheduler = SourceRefreshScheduler(context, sourceStore)
        val controllerConnector = MuxTvMediaControllerConnector(context)

        try {
            setNavigationContent(
                context = context,
                sourceStore = sourceStore,
                scheduler = scheduler,
                controllerConnector = controllerConnector,
            )
            navigateHomeToSettings()
            composeRule.captureScreenshot("settings-sections")
            composeRule.onNodeWithTag(SETTINGS_SOURCES_TEST_TAG).assertIsFocused().press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("sources-add").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("sources-add").assertIsFocused()
            composeRule.captureScreenshot("sources-list")

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(SETTINGS_SOURCES_TEST_TAG).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag(SETTINGS_SOURCES_TEST_TAG).assertIsFocused()
        } finally {
            controllerConnector.close()
        }
    }

    @Test
    fun sourceDetailsCloseRestoresConfigureFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceStore = StaticSourceRefreshStore().apply { publish("Домашний IPTV") }
        val scheduler = SourceRefreshScheduler(context, sourceStore)
        val controllerConnector = MuxTvMediaControllerConnector(context)

        try {
            setNavigationContent(
                context = context,
                sourceStore = sourceStore,
                scheduler = scheduler,
                controllerConnector = controllerConnector,
            )
            navigateHomeToSettings()
            composeRule.onNodeWithTag(SETTINGS_SOURCES_TEST_TAG).press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-configure-source-settings").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("source-configure-source-settings")
                .assertIsFocused()
                .press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-details").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("source-details-close").assertIsFocused()
            composeRule.captureScreenshot("source-details-sheet")

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-details").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("source-configure-source-settings").assertIsFocused()
        } finally {
            controllerConnector.close()
        }
    }

    @Test
    fun doctorBackRestoresDoctorSectionFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceStore = StaticSourceRefreshStore().apply { publish("Домашний IPTV") }
        val scheduler = SourceRefreshScheduler(context, sourceStore)
        val controllerConnector = MuxTvMediaControllerConnector(context)

        try {
            setNavigationContent(
                context = context,
                sourceStore = sourceStore,
                scheduler = scheduler,
                controllerConnector = controllerConnector,
            )
            navigateHomeToSettings()
            composeRule.onNodeWithTag(SETTINGS_SOURCES_TEST_TAG)
                .assertIsFocused()
                .press(Key.DirectionDown)
            composeRule.onNodeWithTag("settings-section-doctor")
                .assertIsFocused()
                .press(Key.Enter)

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("doctor-refresh").fetchSemanticsNodes().size == 1
            }
            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("settings-section-doctor").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("settings-section-doctor").assertIsFocused()
        } finally {
            controllerConnector.close()
        }
    }

    @Test
    fun sourceDetailsAt720pKeepsTopAndBottomActionsReachableByDpad() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceStore = StaticSourceRefreshStore().apply {
            publish("Очень длинное название домашнего IPTV источника для телевизора")
        }
        val scheduler = SourceRefreshScheduler(context, sourceStore)
        val controllerConnector = MuxTvMediaControllerConnector(context)

        setDisplaySize("1280x720")
        try {
            setNavigationContent(
                context = context,
                sourceStore = sourceStore,
                scheduler = scheduler,
                controllerConnector = controllerConnector,
            )
            navigateHomeToSettings()
            composeRule.onNodeWithTag(SETTINGS_SOURCES_TEST_TAG).press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-configure-source-settings").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("source-configure-source-settings").press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("source-details-close").fetchSemanticsNodes().size == 1
            }

            composeRule.onNodeWithTag("source-details-close")
                .assertIsFocused()
                .assertIsDisplayed()
                .press(Key.DirectionUp, 6)
            composeRule.onNodeWithText("Расписание: выключено")
                .assertIsFocused()
                .assertIsDisplayed()

            composeRule.onNodeWithText("Расписание: выключено").press(Key.DirectionDown, 6)
            composeRule.onNodeWithTag("source-details-close")
                .assertIsFocused()
                .assertIsDisplayed()
        } finally {
            setDisplaySize("reset")
            controllerConnector.close()
        }
    }

    @Test
    fun doctorSectionOpensDiagnostics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceStore = StaticSourceRefreshStore().apply { publish("Домашний IPTV") }
        val scheduler = SourceRefreshScheduler(context, sourceStore)
        val controllerConnector = MuxTvMediaControllerConnector(context)

        try {
            setNavigationContent(
                context = context,
                sourceStore = sourceStore,
                scheduler = scheduler,
                controllerConnector = controllerConnector,
            )
            navigateHomeToSettings()
            composeRule.onNodeWithTag("settings-section-doctor").press(Key.Enter)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("doctor-refresh").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("doctor-refresh").assertIsFocused()
        } finally {
            controllerConnector.close()
        }
    }

    private fun setNavigationContent(
        context: Context,
        sourceStore: StaticSourceRefreshStore,
        scheduler: SourceRefreshScheduler,
        controllerConnector: MuxTvMediaControllerConnector,
    ) {
        composeRule.setContent {
            MuxTvTheme {
                AppNavigation(
                    playbackCatalog = StaticPlaybackCatalogFixture,
                    channelBrowseRepository = TestChannelBrowseRepository(
                        StaticPlaybackCatalogFixture,
                        NoRecentChannelsRepository,
                        NoGuideEpgGuideRepository,
                    ),
                    channelPreferencesRepository = NoChannelPreferencesRepository,
                    channelSearchRepository = NoChannelSearchRepository,
                    guideWindowRepository = TestGuideWindowRepository,
                    recentChannelsRepository = NoRecentChannelsRepository,
                    epgGuideRepository = NoGuideEpgGuideRepository,
                    controllerConnector = controllerConnector,
                    sourceRefreshStore = sourceStore,
                    sourceRefreshScheduler = scheduler,
                    sourceEntryOnboarding = UnusedSourceEntryOnboardingFixture,
                    playbackObservationReader = PlaybackObservationReader { emptyList() },
                )
            }
        }
    }

    private fun navigateHomeToSettings() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("home-hero").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("home-hero").assertIsFocused().press(Key.DirectionLeft)
        composeRule.onNodeWithTag("nav-home").assertIsFocused().press(Key.DirectionDown, 4)
        composeRule.onNodeWithTag("nav-settings").assertIsFocused().press(Key.Enter)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(SETTINGS_SOURCES_TEST_TAG).fetchSemanticsNodes().size == 1
        }
    }

    private fun setDisplaySize(size: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("wm size $size")
            .close()
    }
}

private class StaticSourceRefreshStore : SourceRefreshStore {
    private val overviews = MutableStateFlow<List<SourceRefreshOverview>>(emptyList())

    fun publish(sourceName: String) {
        overviews.value = listOf(
            SourceRefreshOverview(
                sourceId = "source-settings",
                sourceName = sourceName,
                hasCredentialReference = true,
                activeRevision = 2,
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

private object StaticPlaybackCatalogFixture : PlaybackCatalog {
    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        flowOf(
            listOf(
                PlayableChannelSummary(
                    channelId = "channel-settings",
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

private object UnusedSourceEntryOnboardingFixture : SourceEntryOnboarding {
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

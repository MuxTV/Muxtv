package app.muxtv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.Density
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.channels.ChannelsRoute
import app.muxtv.feature.settings.SETTINGS_SOURCES_TEST_TAG
import app.muxtv.feature.settings.SettingsRoute
import org.junit.Rule
import org.junit.Test

/**
 * Large-text and reduced-motion reachability: every state must stay D-pad
 * reachable with fontScale 1.3 and no clipped focus targets. Motion is token
 * driven (no scale on rows, animated only where reduced-motion scale applies).
 */
class AccessibilityJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeTextSettingsSectionsStayReachable() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f),
            ) {
                MuxTvTheme {
                    SettingsRoute(
                        onOpenSources = {},
                        onOpenDoctor = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_SOURCES_TEST_TAG)
            .assertIsFocused()
            .press(Key.DirectionDown)
        composeRule.onNodeWithTag("settings-section-doctor").assertIsFocused()
    }

    @Test
    fun largeTextChannelRowsStayReachable() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f),
            ) {
                MuxTvTheme {
                    ChannelsRoute(
                        channelBrowseRepository = TestChannelBrowseRepository(
                            AccessibilityPlaybackCatalogFixture,
                            NoRecentChannelsRepository,
                            StaticNowNextEpgGuideRepository(
                                channelId = "channel-a11y-1",
                                currentTitle = "Новости",
                                nextTitle = "Погода",
                            ),
                        ),
                        epgGuideRepository = StaticNowNextEpgGuideRepository(
                            channelId = "channel-a11y-1",
                            currentTitle = "Новости",
                            nextTitle = "Погода",
                        ),
                        playbackSessionStateSource = NoPlaybackSessionStateSource,
                        profileId = "profile-main",
                        onOpenChannel = {},
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("channel-row-0").fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0")
            .assertIsFocused()
            .press(Key.DirectionUp)
        composeRule.onNodeWithTag("channels-filter-all").assertIsFocused()
    }

    @Test
    fun largeTextHomeEmptyStateActionReachable() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f),
            ) {
                MuxTvTheme {
                    app.muxtv.feature.home.HomeRoute(
                        channelBrowseRepository = EmptyCatalogFixture,
                        recentChannelsRepository = NoRecentChannelsRepository,
                        epgGuideRepository = NoGuideEpgGuideRepository,
                        playbackSessionStateSource = NoPlaybackSessionStateSource,
                        hasSources = kotlinx.coroutines.flow.flowOf(false),
                        profileId = "profile-main",
                        onOpenChannel = {},
                        onOpenChannels = {},
                        onOpenGuide = {},
                        onAddSource = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home-add-source").assertIsFocused()
    }
}

private object AccessibilityPlaybackCatalogFixture : app.muxtv.catalog.PlaybackCatalog {
    override fun observeChannels(query: app.muxtv.catalog.ChannelQuery):
        kotlinx.coroutines.flow.Flow<List<app.muxtv.catalog.PlayableChannelSummary>> =
        kotlinx.coroutines.flow.flowOf(
            (1..3).map { index ->
                app.muxtv.catalog.PlayableChannelSummary(
                    channelId = "channel-a11y-$index",
                    displayName = "Очень длинное название канала номер $index",
                    logoUrl = null,
                    groupTitle = "Длинная группа",
                    channelNumber = index.toString(),
                    isFavorite = false,
                    variantCount = 1,
                )
            },
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

private object EmptyCatalogFixture : app.muxtv.catalog.ChannelBrowseRepository {
    override fun pages(query: app.muxtv.catalog.ChannelBrowseQuery):
        kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<app.muxtv.catalog.ChannelBrowseItem>> =
        kotlinx.coroutines.flow.flowOf(androidx.paging.PagingData.empty())
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

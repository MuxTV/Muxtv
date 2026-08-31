package app.muxtv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
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
import app.muxtv.feature.channels.ChannelsRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test

class ChannelQuickActionsJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalOkStillOpensPlaybackWhenQuickActionsAreEnabled() {
        val catalog = QuickActionPlaybackCatalog()
        var openedChannelId: String? = null

        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(
                    catalog = catalog,
                    preferences = HidingPreferencesRepository(catalog),
                    onOpenChannel = { openedChannelId = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitForIdle()

        assertThat(openedChannelId).isEqualTo("channel-a")
        composeRule.onNodeWithTag("channel-quick-actions").assertDoesNotExist()
    }

    @Test
    fun longClickOpensQuickActionsWithoutStartingPlayback() {
        val catalog = QuickActionPlaybackCatalog()
        var openedChannelId: String? = null

        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(
                    catalog = catalog,
                    preferences = HidingPreferencesRepository(catalog),
                    onOpenChannel = { openedChannelId = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()

        assertThat(openedChannelId).isNull()
        composeRule.onNodeWithTag("channel-quick-actions").assertExists()
        composeRule.onNodeWithText("В избранное").assertIsFocused()
    }

    @Test
    fun hidingFocusedChannelReturnsFocusToNearestPreviousRow() {
        val catalog = QuickActionPlaybackCatalog()
        val preferences = HidingPreferencesRepository(catalog)

        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(
                    catalog = catalog,
                    preferences = preferences,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
        composeRule.onNodeWithTag("channel-row-1")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Скрыть").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Второй", substring = false).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("channel-quick-actions").assertDoesNotExist()
        composeRule.onNodeWithText("Первый", substring = false).assertIsFocused()
    }

    @androidx.compose.runtime.Composable
    private fun channelsRoute(
        catalog: QuickActionPlaybackCatalog,
        preferences: ChannelPreferencesRepository,
        onOpenChannel: (String) -> Unit = {},
    ) {
        ChannelsRoute(
            channelBrowseRepository = TestChannelBrowseRepository(
                playbackCatalog = catalog,
                recentChannelsRepository = EmptyRecentChannelsRepository,
                epgGuideRepository = NoGuideEpgGuideRepository,
            ),
            epgGuideRepository = NoGuideEpgGuideRepository,
            playbackSessionStateSource = NoPlaybackSessionStateSource,
            profileId = PROFILE_ID,
            onOpenChannel = onOpenChannel,
            channelPreferencesRepository = preferences,
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
    }
}

private class HidingPreferencesRepository(
    private val catalog: QuickActionPlaybackCatalog,
) : ChannelPreferencesRepository {
    override suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult = ChannelFavoriteMutationResult.Applied

    override suspend fun setHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): ChannelPreferenceMutationResult {
        if (isHidden) catalog.remove(channelId)
        return ChannelPreferenceMutationResult.Applied
    }

    override suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.Applied

    override suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.Applied

    override suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.Applied
}

private class QuickActionPlaybackCatalog : PlaybackCatalog {
    private val channels = MutableStateFlow(
        listOf(
            quickActionChannel("channel-a", "Первый"),
            quickActionChannel("channel-b", "Второй"),
            quickActionChannel("channel-c", "Третий"),
        ),
    )

    fun remove(channelId: String) {
        channels.value = channels.value.filterNot { it.channelId == channelId }
    }

    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        channels.map { rows ->
            rows.filter { row -> !query.favoritesOnly || row.isFavorite }
                .take(query.limit)
        }

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

private object EmptyRecentChannelsRepository : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> = flowOf(emptyList())

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult = RecentChannelWriteResult.Applied
}

private fun quickActionChannel(
    id: String,
    name: String,
): PlayableChannelSummary = PlayableChannelSummary(
    channelId = id,
    displayName = name,
    logoUrl = null,
    groupTitle = "Тест",
    channelNumber = null,
    isFavorite = false,
    variantCount = 1,
)

private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressEnter() = performKeyInput {
    keyDown(Key.Enter)
    keyUp(Key.Enter)
}

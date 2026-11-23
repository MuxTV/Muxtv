package app.muxtv

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelQuickActionsJourneyTest {
    private val effectScheduler = TestCoroutineScheduler()

    @get:Rule
    val composeRule = createComposeRule(
        effectContext = StandardTestDispatcher(effectScheduler),
    )

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
        drainEffects()

        composeRule.onNodeWithTag("channel-row-0")
            .assertIsFocused()
            .pressEnter()
        drainEffects()

        assertThat(openedChannelId).isEqualTo("channel-a")
        composeRule.onNodeWithTag("channel-quick-actions").assertDoesNotExist()
    }

    @Test
    fun shortDpadCenterStillStartsPlaybackInsteadOfQuickActions() {
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
        drainEffects()

        composeRule.onNodeWithTag("channel-row-0").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(80L)
            keyUp(Key.DirectionCenter)
        }
        drainEffects()

        assertThat(openedChannelId).isEqualTo("channel-a")
        composeRule.onNodeWithTag("channel-quick-actions").assertDoesNotExist()
    }

    @Test
    fun heldDpadCenterOpensQuickActionsWithoutStartingPlayback() {
        val catalog = QuickActionPlaybackCatalog()
        var openedChannelId: String? = null
        lateinit var inputModeManager: InputModeManager

        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            MuxTvTheme {
                channelsRoute(
                    catalog = catalog,
                    preferences = HidingPreferencesRepository(catalog),
                    onOpenChannel = { openedChannelId = it },
                )
            }
        }
        drainEffects()

        // A held remote key spans input batches so the modifier's coroutine
        // timer runs while Center is still down. The custom effectContext uses
        // a TestCoroutineScheduler independent of the key-injection clock.
        // Compose also synthesizes real key repeats while advancing event time.
        composeRule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
        var holdDurationMillis = 0L
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused().performKeyInput {
            holdDurationMillis = viewConfiguration.longPressTimeoutMillis + 100L
            keyDown(Key.DirectionCenter)
            advanceEventTime(holdDurationMillis)
        }
        effectScheduler.runCurrent()
        effectScheduler.advanceTimeBy(holdDurationMillis)
        effectScheduler.runCurrent()
        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyUp(Key.DirectionCenter)
        }
        drainEffects()

        assertThat(openedChannelId).isNull()
        composeRule.onNodeWithTag("channel-quick-actions").assertExists()
        composeRule.onNodeWithText("В избранное").assertIsFocused()
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
        drainEffects()

        composeRule.onNodeWithTag("channel-row-0")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        drainEffects()

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
        drainEffects()

        composeRule.onNodeWithTag("channel-row-0").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("channel-row-1").assertIsFocused()
        composeRule.onNodeWithTag("channel-row-1")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        drainEffects()
        // Drive the same focus path as a real TV remote.
        composeRule.onNodeWithText("В избранное").assertIsFocused().pressDown()
        composeRule.onNodeWithText("Скрыть").assertIsFocused().pressEnter()
        drainEffects()
        assertThat(preferences.hideRequests).isEqualTo(1)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(
                "Долгое OK · быстрые действия",
                substring = false,
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Второй", substring = false).fetchSemanticsNodes().isEmpty()
        }
        drainEffects()

        composeRule.onNodeWithText("Первый", substring = false).assertIsFocused()
    }

    @Test
    fun unchangedHideRestoresFocusWithoutWaitingForChannelRemoval() {
        val catalog = QuickActionPlaybackCatalog()
        val preferences = HidingPreferencesRepository(catalog, applyHide = false)

        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(catalog = catalog, preferences = preferences)
            }
        }
        drainEffects()

        composeRule.onNodeWithTag("channel-row-0")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        drainEffects()
        composeRule.onNodeWithText("В избранное").assertIsFocused().pressDown()
        composeRule.onNodeWithText("Скрыть").assertIsFocused().pressEnter()
        drainEffects()
        assertThat(preferences.hideRequests).isEqualTo(1)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(
                "Долгое OK · быстрые действия",
                substring = false,
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("channel-row-0").assertIsFocused()
        composeRule.onNodeWithText("Первый", substring = false).assertExists()
    }

    @Test
    fun invalidCustomNumberKeepsQuickEditorOpenForCorrection() {
        val catalog = QuickActionPlaybackCatalog()

        composeRule.setContent {
            MuxTvTheme {
                channelsRoute(
                    catalog = catalog,
                    preferences = HidingPreferencesRepository(catalog),
                )
            }
        }
        drainEffects()

        composeRule.onNodeWithTag("channel-row-0")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        drainEffects()
        composeRule.onNodeWithText("В избранное").assertIsFocused().pressDown()
        composeRule.onNodeWithText("Скрыть").assertIsFocused().pressDown()
        composeRule.onNodeWithText("Переименовать").assertIsFocused().pressDown()
        composeRule.onNodeWithText("Номер").assertIsFocused().pressEnter()
        drainEffects()
        composeRule.onNodeWithTag("channel-quick-actions-editor")
            .assertIsFocused()
            .performTextInput("10000")
        // The editor owns TV focus. Move to Save via D-pad before pressing OK:
        // performing a semantics click on an unfocused TV Button does not
        // exercise the actual remote interaction contract.
        composeRule.onNodeWithTag("channel-quick-actions-editor").pressDown()
        composeRule.onNodeWithText("Сохранить").assertIsFocused().pressUp()
        composeRule.onNodeWithTag("channel-quick-actions-editor").assertIsFocused().pressDown()
        composeRule.onNodeWithText("Сохранить").assertIsFocused().pressEnter()
        drainEffects()

        composeRule.onNodeWithTag("channel-quick-actions-error").assertExists()
        composeRule.onNodeWithTag("channel-quick-actions-editor").assertExists()
        composeRule.onNodeWithTag("channel-quick-actions").assertExists()
        composeRule.onNodeWithText("Введите номер от 1 до 9999.").assertExists()
    }

    private fun drainEffects() {
        effectScheduler.runCurrent()
        composeRule.waitForIdle()
        effectScheduler.runCurrent()
        composeRule.waitForIdle()
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
    private val applyHide: Boolean = true,
) : ChannelPreferencesRepository {
    var hideRequests: Int = 0
        private set

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
        hideRequests++
        if (!applyHide) return ChannelPreferenceMutationResult.Unchanged
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
    ): ChannelPreferenceMutationResult =
        if (channelNumber != null && channelNumber !in 1..9999) {
            ChannelPreferenceMutationResult.InvalidInput
        } else {
            ChannelPreferenceMutationResult.Applied
        }

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

private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressDown() = performKeyInput {
    keyDown(Key.DirectionDown)
    keyUp(Key.DirectionDown)
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressUp() = performKeyInput {
    keyDown(Key.DirectionUp)
    keyUp(Key.DirectionUp)
}

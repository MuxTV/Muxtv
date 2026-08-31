package app.muxtv

import androidx.paging.PagingData
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelManagementItem
import app.muxtv.catalog.ChannelManagementQuery
import app.muxtv.catalog.ChannelManagementVisibility
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.channels.ManageChannelsRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test

class ManageChannelsAcceptanceJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hiddenChannelCanBeRecoveredAndFocusFallsBackToNearestPreviousHiddenRow() {
        val fixture = ManageChannelsFixture(
            initialRows = listOf(
                managementChannel(
                    id = "channel-a",
                    canonicalName = "Первый",
                    number = "1",
                    isHidden = true,
                ),
                managementChannel(
                    id = "channel-b",
                    canonicalName = "Второй",
                    number = "2",
                    isHidden = true,
                ),
                managementChannel(
                    id = "channel-c",
                    canonicalName = "Третий",
                    number = "3",
                    isHidden = false,
                ),
            ),
        )

        composeRule.setContent {
            MuxTvTheme {
                ManageChannelsRoute(
                    channelBrowseRepository = fixture,
                    channelPreferencesRepository = fixture,
                    profileId = PROFILE_ID,
                )
            }
        }

        composeRule.onNodeWithTag("manage-channels-filter-hidden").performClick()
        composeRule.waitUntilTag("manage-channel-row-channel-b")
        composeRule.onNodeWithTag("manage-channel-row-channel-b").performClick()
        composeRule.onNodeWithText("Показать").assertIsFocused().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("manage-channels-actions").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("manage-channel-row-channel-b").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("manage-channel-row-channel-a").assertIsFocused()
        composeRule.onNodeWithTag("manage-channel-row-channel-c").assertDoesNotExist()
    }

    @Test
    fun renameNumberAndResetRoundTripKeepsFavoriteState() {
        val fixture = ManageChannelsFixture(
            initialRows = listOf(
                managementChannel(
                    id = "channel-news",
                    canonicalName = "Новости",
                    number = "10",
                    isFavorite = true,
                ),
            ),
        )

        composeRule.setContent {
            MuxTvTheme {
                ManageChannelsRoute(
                    channelBrowseRepository = fixture,
                    channelPreferencesRepository = fixture,
                    profileId = PROFILE_ID,
                )
            }
        }
        composeRule.waitUntilTag("manage-channel-row-channel-news")

        composeRule.onNodeWithTag("manage-channel-row-channel-news").performClick()
        composeRule.onNodeWithText("Переименовать").performClick()
        composeRule.onNodeWithTag("manage-channels-editor")
            .assertIsFocused()
            .performTextClearance()
            .performTextInput("Мои новости")
        composeRule.onNodeWithText("Сохранить").performClick()
        composeRule.waitUntilText("Мои новости")

        composeRule.onNodeWithTag("manage-channel-row-channel-news").performClick()
        composeRule.onNodeWithText("Номер").performClick()
        composeRule.onNodeWithTag("manage-channels-editor")
            .assertIsFocused()
            .performTextInput("77")
        composeRule.onNodeWithText("Сохранить").performClick()
        composeRule.waitUntilText("77")
        composeRule.onNodeWithText("Видим · Избранное · Изменён").assertExists()

        composeRule.onNodeWithTag("manage-channel-row-channel-news").performClick()
        composeRule.onNodeWithText("Сбросить").performClick()
        composeRule.waitUntilText("Новости")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("10").assertExists()
        composeRule.onNodeWithText("Видим · Избранное").assertExists()
        composeRule.onNodeWithText("Изменён", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("manage-channel-row-channel-news").assertIsFocused()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilText(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("manage-channels-actions").fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
    }
}

private class ManageChannelsFixture(
    initialRows: List<ChannelManagementItem>,
) : ChannelBrowseRepository, ChannelPreferencesRepository {
    private val rows = MutableStateFlow(initialRows)

    override fun pages(query: ChannelBrowseQuery): Flow<PagingData<ChannelBrowseItem>> =
        flowOf(PagingData.empty())

    override fun managementPages(query: ChannelManagementQuery): Flow<PagingData<ChannelManagementItem>> =
        rows.map { current ->
            val filtered = when (query.visibility) {
                ChannelManagementVisibility.ALL -> current
                ChannelManagementVisibility.VISIBLE -> current.filterNot(ChannelManagementItem::isHidden)
                ChannelManagementVisibility.HIDDEN -> current.filter(ChannelManagementItem::isHidden)
            }
            PagingData.from(filtered)
        }

    override suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult =
        updateVisible(channelId) { row -> row.copy(isFavorite = isFavorite) }
            .toFavoriteResult()

    override suspend fun setHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): ChannelPreferenceMutationResult =
        update(channelId) { row -> row.copy(isHidden = isHidden) }

    override suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult {
        val normalized = customName?.trim()
        if (normalized != null && normalized.isBlank()) return ChannelPreferenceMutationResult.InvalidInput
        return update(channelId) { row ->
            row.copy(effectiveDisplayName = normalized ?: row.canonicalDisplayName)
        }
    }

    override suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult {
        if (channelNumber != null && channelNumber !in 1..9999) {
            return ChannelPreferenceMutationResult.InvalidInput
        }
        return update(channelId) { row ->
            row.copy(
                customChannelNumber = channelNumber,
                effectiveChannelNumber = channelNumber?.toString() ?: row.defaultChannelNumber,
            )
        }
    }

    override suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): ChannelPreferenceMutationResult =
        update(channelId) { row ->
            row.copy(
                effectiveDisplayName = row.canonicalDisplayName,
                customChannelNumber = null,
                effectiveChannelNumber = row.defaultChannelNumber,
                isHidden = false,
            )
        }

    private fun updateVisible(
        channelId: String,
        transform: (ChannelManagementItem) -> ChannelManagementItem,
    ): ChannelPreferenceMutationResult {
        val current = rows.value
        val index = current.indexOfFirst { row -> row.channelId == channelId && !row.isHidden }
        if (index < 0) return ChannelPreferenceMutationResult.NotFound
        return replaceAt(index, transform)
    }

    private fun update(
        channelId: String,
        transform: (ChannelManagementItem) -> ChannelManagementItem,
    ): ChannelPreferenceMutationResult {
        val current = rows.value
        val index = current.indexOfFirst { row -> row.channelId == channelId }
        if (index < 0) return ChannelPreferenceMutationResult.NotFound
        return replaceAt(index, transform)
    }

    private fun replaceAt(
        index: Int,
        transform: (ChannelManagementItem) -> ChannelManagementItem,
    ): ChannelPreferenceMutationResult {
        val current = rows.value
        val before = current[index]
        val after = transform(before)
        if (before == after) return ChannelPreferenceMutationResult.Unchanged
        rows.value = current.toMutableList().also { it[index] = after }
        return ChannelPreferenceMutationResult.Applied
    }

    private fun ChannelPreferenceMutationResult.toFavoriteResult(): ChannelFavoriteMutationResult = when (this) {
        ChannelPreferenceMutationResult.Applied -> ChannelFavoriteMutationResult.Applied
        ChannelPreferenceMutationResult.Unchanged -> ChannelFavoriteMutationResult.Unchanged
        ChannelPreferenceMutationResult.NotFound -> ChannelFavoriteMutationResult.NotFound
        ChannelPreferenceMutationResult.InvalidInput -> ChannelFavoriteMutationResult.NotFound
    }
}

private fun managementChannel(
    id: String,
    canonicalName: String,
    number: String,
    isFavorite: Boolean = false,
    isHidden: Boolean = false,
): ChannelManagementItem = ChannelManagementItem(
    channelId = id,
    canonicalDisplayName = canonicalName,
    effectiveDisplayName = canonicalName,
    defaultChannelNumber = number,
    customChannelNumber = null,
    effectiveChannelNumber = number,
    isFavorite = isFavorite,
    isHidden = isHidden,
    variantCount = 1,
)

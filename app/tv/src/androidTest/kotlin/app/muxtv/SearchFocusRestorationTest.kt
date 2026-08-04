package app.muxtv

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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.ChannelSearchResult
import app.muxtv.catalog.ChannelSearchSnapshot
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.search.SearchRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test

class SearchFocusRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inputDpadAndOkOpenCanonicalSearchResult() {
        var openedChannelId: String? = null
        composeRule.setContent {
            MuxTvTheme {
                SearchRoute(
                    repository = MutableSearchRepository(),
                    profileId = "profile-main",
                    onOpenChannel = { channelId -> openedChannelId = channelId },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search-input")
            .assertIsFocused()
            .performTextInput("Первый")
        composeRule.waitUntilSearchResult("channel-a")

        composeRule.onNodeWithTag("search-input").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag(searchResultTag("channel-a"))
            .assertIsFocused()
            .pressEnter()

        composeRule.runOnIdle {
            check(openedChannelId == "channel-a")
        }
    }

    @Test
    fun queryAndCanonicalFocusRestoreAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    SearchTestPlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("search") {
                        SearchRoute(
                            repository = MutableSearchRepository(),
                            profileId = "profile-main",
                            onOpenChannel = { playerOpen.value = true },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        enterQueryAndFocusSecondResult()
        composeRule.onNodeWithTag(searchResultTag("channel-b")).pressEnter()
        composeRule.waitUntilPlayerBack()
        composeRule.onNodeWithTag("search-test-player-back")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitUntilSearchResult("channel-b")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(searchResultTag("channel-b")).assertIsFocused()
    }

    @Test
    fun removedFocusedResultFallsBackToNearestPreviousAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        val repository = MutableSearchRepository()
        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    SearchTestPlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("search") {
                        SearchRoute(
                            repository = repository,
                            profileId = "profile-main",
                            onOpenChannel = { playerOpen.value = true },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        enterQueryAndFocusSecondResult()
        composeRule.onNodeWithTag(searchResultTag("channel-b")).pressEnter()
        composeRule.waitUntilPlayerBack()

        repository.remove("channel-b")
        composeRule.onNodeWithTag("search-test-player-back")
            .assertIsFocused()
            .pressEnter()
        composeRule.waitUntilSearchResult("channel-a")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(searchResultTag("channel-a")).assertIsFocused()
    }

    @Test
    fun emptyResultReturnsFocusToInput() {
        val repository = MutableSearchRepository()
        composeRule.setContent {
            MuxTvTheme {
                SearchRoute(
                    repository = repository,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search-input").performTextInput("Первый")
        composeRule.waitUntilSearchResult("channel-a")
        composeRule.onNodeWithTag("search-input").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag(searchResultTag("channel-a")).assertIsFocused()

        repository.clear()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(searchResultTag("channel-a"))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search-input").assertIsFocused()
    }

    private fun enterQueryAndFocusSecondResult() {
        composeRule.onNodeWithTag("search-input")
            .assertIsFocused()
            .performTextInput("канал")
        composeRule.waitUntilSearchResult("channel-b")
        composeRule.onNodeWithTag("search-input").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        composeRule.onNodeWithTag(searchResultTag("channel-b")).assertIsFocused()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilSearchResult(
        channelId: String,
    ) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(searchResultTag(channelId)).fetchSemanticsNodes().size == 1
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilPlayerBack() {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("search-test-player-back").fetchSemanticsNodes().size == 1
        }
    }
}

@androidx.compose.runtime.Composable
private fun SearchTestPlayer(onBack: () -> Unit) {
    val backFocusRequester = remember { FocusRequester() }
    LaunchedEffect(backFocusRequester) {
        withFrameNanos { }
        backFocusRequester.requestFocus()
    }
    MuxTvActionButton(
        text = "Назад к поиску",
        onClick = onBack,
        modifier = Modifier
            .testTag("search-test-player-back")
            .focusRequester(backFocusRequester),
    )
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressEnter() = performKeyInput {
    keyDown(Key.Enter)
    keyUp(Key.Enter)
}

private fun searchResultTag(channelId: String): String = "search-result-$channelId"

private class MutableSearchRepository(
    initialRows: List<ChannelSearchResult> = searchResults,
) : ChannelSearchRepository {
    private val rows = MutableStateFlow(initialRows)

    fun remove(channelId: String) {
        rows.value = rows.value.filterNot { result -> result.channel.channelId == channelId }
    }

    fun clear() {
        rows.value = emptyList()
    }

    override fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot> =
        rows.map { currentRows ->
            ChannelSearchSnapshot(
                results = currentRows,
                isTruncated = false,
                nextBoundaryEpochMillis = null,
            )
        }
}

private val searchResults = listOf(
    searchResult("channel-a", "Первый канал", "1"),
    searchResult("channel-b", "Второй канал", "2"),
    searchResult("channel-c", "Третий канал", "3"),
)

private fun searchResult(
    channelId: String,
    displayName: String,
    channelNumber: String,
): ChannelSearchResult = ChannelSearchResult(
    channel = PlayableChannelSummary(
        channelId = channelId,
        displayName = displayName,
        logoUrl = null,
        groupTitle = "Тест",
        channelNumber = channelNumber,
        isFavorite = false,
        variantCount = 1,
    ),
    currentProgrammeTitle = "Текущая программа",
)

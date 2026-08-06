package app.muxtv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import app.muxtv.catalog.ChannelGuideProgrammeWindow
import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.guide.GuideRoute
import app.muxtv.designsystem.component.MuxTvActionButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test

class GuideFocusJourneyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dpadOkOpensCanonicalChannelFromProgrammeCell() {
        var openedChannelId: String? = null
        val repository = JourneyGuideRepository()

        composeRule.setContent {
            MuxTvTheme {
                GuideRoute(
                    repository = repository,
                    profileId = "profile-main",
                    onOpenChannel = { openedChannelId = it },
                )
            }
        }
        composeRule.waitUntilGuideCell()

        composeRule.onNodeWithTag("guide-cell-0-0")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.runOnIdle {
            check(openedChannelId == GUIDE_CHANNEL_A)
        }
    }

    @Test
    fun canonicalProgrammeFocusRestoresAfterPlayerBack() {
        val playerOpen = mutableStateOf(false)
        val repository = JourneyGuideRepository()

        composeRule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            MuxTvTheme {
                if (playerOpen.value) {
                    GuideTestPlayer(onBack = { playerOpen.value = false })
                } else {
                    stateHolder.SaveableStateProvider("guide") {
                        GuideRoute(
                            repository = repository,
                            profileId = "profile-main",
                            onOpenChannel = { playerOpen.value = true },
                        )
                    }
                }
            }
        }
        composeRule.waitUntilGuideCell()

        composeRule.onNodeWithTag("guide-cell-0-0")
            .assertIsFocused()
            .press(Key.Enter)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onNodeWithTag("guide-test-player-back")
                .fetchSemanticsNodeOrNull() != null
        }
        composeRule.onNodeWithTag("guide-test-player-back")
            .assertIsFocused()
            .press(Key.Enter)
        composeRule.waitUntilGuideCell()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("guide-cell-0-0").assertIsFocused()
    }

    @Test
    fun noGuideAndSourceConflictRemainExplicitFocusableRows() {
        val repository = JourneyGuideRepository(
            states = listOf(
                GuideProjectionState.NO_GUIDE,
                GuideProjectionState.SOURCE_CONFLICT,
            ),
        )

        composeRule.setContent {
            MuxTvTheme {
                GuideRoute(
                    repository = repository,
                    profileId = "profile-main",
                    onOpenChannel = {},
                )
            }
        }
        composeRule.waitUntilGuideCell()

        composeRule.onNodeWithText("Нет программы").assertExists()
        composeRule.onNodeWithText("Конфликт источников").assertExists()
        composeRule.onNodeWithTag("guide-cell-0-0").assertIsFocused()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilGuideCell() {
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag("guide-cell-0-0").fetchSemanticsNodeOrNull() != null
        }
        waitForIdle()
    }
}

@androidx.compose.runtime.Composable
private fun GuideTestPlayer(onBack: () -> Unit) {
    val requester = androidx.compose.ui.focus.FocusRequester()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        requester.requestFocus()
    }
    MuxTvActionButton(
        text = "Назад",
        onClick = onBack,
        modifier = androidx.compose.ui.Modifier
            .testTag("guide-test-player-back")
            .focusRequester(requester),
    )
}

private class JourneyGuideRepository(
    private val states: List<GuideProjectionState> = listOf(GuideProjectionState.READY),
) : GuideWindowRepository {
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    override suspend fun getChannelWindow(query: GuideChannelWindowQuery): GuideChannelWindow =
        GuideChannelWindow(
            channels = states.mapIndexed { index, _ ->
                PlayableChannelSummary(
                    channelId = if (index == 0) GUIDE_CHANNEL_A else "guide-channel-${index + 1}",
                    displayName = "Канал ${index + 1}",
                    logoUrl = null,
                    groupTitle = "Тест",
                    channelNumber = (index + 1).toString(),
                    isFavorite = index == 0,
                    variantCount = 1,
                )
            },
            nextCursor = null,
            isTruncated = false,
        )

    override suspend fun getProgrammeWindow(query: GuideProgrammeWindowQuery): GuideProgrammeWindow =
        GuideProgrammeWindow(
            channels = query.canonicalChannelIds.mapIndexed { index, id ->
                val state = states[index]
                ChannelGuideProgrammeWindow(
                    canonicalChannelId = id,
                    state = state,
                    programmes = if (state == GuideProjectionState.READY) {
                        listOf(
                            GuideProgrammeCell(
                                key = GuideProgrammeKey(
                                    epgSourceId = "epg-test",
                                    epgRevisionNumber = 1,
                                    sequenceNumber = index.toLong(),
                                ),
                                startEpochMillis = query.fromEpochMillis,
                                endEpochMillis = minOf(
                                    query.toEpochMillis,
                                    query.fromEpochMillis + 30L * 60L * 1_000L,
                                ),
                                title = "Передача ${index + 1}",
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            isTruncated = false,
        )

    override fun observeDataChanges(): Flow<Unit> = invalidations
}

private fun SemanticsNodeInteraction.press(key: Key): SemanticsNodeInteraction = apply {
    performKeyInput {
        keyDown(key)
        keyUp(key)
    }
}

private const val GUIDE_CHANNEL_A = "guide-channel-a"

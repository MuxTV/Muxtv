package app.muxtv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
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
import app.muxtv.feature.sources.AddSourceRoute
import app.muxtv.feature.sources.SourcesRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class SourceEntryFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptySourcesStartsOnAddSource() {
        composeRule.setContent {
            MuxTvTheme {
                SourcesRoute(
                    sourceManagement = EmptySourceManagement,
                    onAddSource = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sources-add").assertIsFocused()
    }

    @Test
    fun editingStartsOnSourceName() {
        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = HttpApprovalOnboarding,
                    onCompleted = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-name").assertIsFocused()
    }

    @Test
    fun httpWarningFocusesSafeCancelAction() {
        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = HttpApprovalOnboarding,
                    onCompleted = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-locator")
            .performSemanticsAction(SemanticsActions.SetText) { action ->
                action(AnnotatedString("http://192.168.1.10/list.m3u"))
            }
        composeRule.onNodeWithText("Проверить")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("source-http-cancel")
                .fetchSemanticsNodes()
                .size == 1
        }

        composeRule.onNodeWithTag("source-http-cancel").assertIsFocused()
    }
}

private object HttpApprovalOnboarding : SourceOnboarding {
    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = SourcePreparationResult.InsecureTransportApprovalRequired

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

private object EmptySourceManagement : SourceManagement {
    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> = flowOf(emptyList())

    override fun refreshNow(sourceId: String) = Unit

    override suspend fun updatePolicy(policy: SourceRefreshPolicy) = Unit

    override suspend fun removePolicy(sourceId: String) = Unit

    override suspend fun revokePlaybackApprovals(
        sourceId: String,
    ): SourcePlaybackApprovalResetResult = SourcePlaybackApprovalResetResult.SourceNotFound
}

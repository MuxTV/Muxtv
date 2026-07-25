package app.muxtv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.AddSourceRoute
import app.muxtv.feature.sources.SourceEntryOnboarding
import org.junit.Rule
import org.junit.Test

class SourceEntryFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

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
            .performClick()
            .performTextInput("http://192.168.1.10/list.m3u")
        composeRule.onNodeWithText("Проверить").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-http-cancel").assertIsFocused()
    }
}

private object HttpApprovalOnboarding : SourceEntryOnboarding {
    override suspend fun prepare(
        input: RemoteSourceOnboardingInput,
    ): RemoteSourcePreparationResult =
        RemoteSourcePreparationResult.InsecureTransportApprovalRequired

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = RemoteSourceActivationResult.Failed(
        failure = RemoteSourceActivationFailure.Unexpected,
        credentialCleanupFailure = null,
        sourceCleanupFailure = null,
    )

    override suspend fun cancel(
        token: RemoteSourcePreparationToken,
    ): RemoteSourceCancellationResult = RemoteSourceCancellationResult.NotFound

    override suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared? = null
}

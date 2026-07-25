package app.muxtv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
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

class SourceEntrySecurityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun locatorIsNotPublishedAsTextOrEditableTextWhileMasked() {
        val secretLocator = "https://provider.example/list.m3u?token=secret-value"

        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = NoOpSourceEntryOnboarding,
                    onCompleted = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-locator")
            .performSemanticsAction(SemanticsActions.SetText) { action ->
                action(AnnotatedString(secretLocator))
            }
        composeRule.onNodeWithTag("source-locator")
            .assertContentDescriptionEquals("Ссылка M3U, значение скрыто")

        composeRule.onAllNodes(
            matcher = hasText(secretLocator, substring = true),
        ).assertCountEquals(0)
        composeRule.onAllNodes(
            matcher = hasText(secretLocator, substring = true),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }
}

private object NoOpSourceEntryOnboarding : SourceEntryOnboarding {
    override suspend fun prepare(
        input: RemoteSourceOnboardingInput,
    ): RemoteSourcePreparationResult = RemoteSourcePreparationResult.InvalidAccess

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

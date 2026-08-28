package app.muxtv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationFailure
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.AddSourceRoute
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

private object NoOpSourceEntryOnboarding : SourceOnboarding {
    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = SourcePreparationResult.Failed(
        SourcePreparationFailure.InvalidLocator,
    )

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

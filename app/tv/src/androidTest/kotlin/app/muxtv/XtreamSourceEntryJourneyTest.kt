package app.muxtv

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.AddSourceRoute
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class XtreamSourceEntryJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun xtreamCanBeSelectedAndPreparedWithoutExposingCredentials() {
        val onboarding = RecordingXtreamOnboarding()

        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = onboarding,
                    onCompleted = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Xtream").assertExists()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Сервер Xtream").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("source-xtream-endpoint")
            .performSemanticsAction(SemanticsActions.SetText) { setText ->
                setText(AnnotatedString("https://provider.example"))
            }
        composeRule.onNodeWithTag("source-xtream-username")
            .performSemanticsAction(SemanticsActions.SetText) { setText ->
                setText(AnnotatedString("alice"))
            }
        composeRule.onNodeWithTag("source-xtream-password")
            .performSemanticsAction(SemanticsActions.SetText) { setText ->
                setText(AnnotatedString("secret"))
            }
        composeRule.onNodeWithTag("source-check")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Подтвердите адрес: https://provider.example")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onAllNodesWithText("alice", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("secret", substring = true).assertCountEquals(0)

        composeRule.runOnIdle {
            assertThat(onboarding.requests).hasSize(1)
            val request = onboarding.requests.single() as SourcePreparationRequest.Xtream
            assertThat(request.endpoint).isEqualTo("https://provider.example")
            assertThat(request.username).isEqualTo("alice")
            assertThat(request.password).isEqualTo("secret")
            assertThat(request.toString()).doesNotContain("alice")
            assertThat(request.toString()).doesNotContain("secret")
        }
    }
}

private class JourneyPreparationHandle : SourcePreparationHandle()

private class RecordingXtreamOnboarding : SourceOnboarding {
    val requests = mutableListOf<SourcePreparationRequest>()

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = error("Xtream journey must use the typed request")

    override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
        requests += request
        return SourcePreparationResult.Prepared(
            handle = JourneyPreparationHandle(),
            displayEndpoint = "https://provider.example",
        )
    }

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult = SourceActivationResult.Failed(
        reason = SourceActivationFailure.Unexpected,
        cleanupPending = false,
    )

    override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult =
        SourceCancellationResult.Removed

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
}

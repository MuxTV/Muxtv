package app.muxtv

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.AddSourceRoute
import app.muxtv.feature.sources.LocalNetworkPermissionOutcome
import org.junit.Rule
import org.junit.Test

class AddSourceLocalNetworkPermissionJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun localM3uGrantReplaysExactRequestBeforeIndependentHttpApproval() {
        val locator = "http://192.168.1.20:8080/list.m3u?token=journey-secret"
        val onboarding = LanThenHttpOnboarding(expectedLocator = locator)
        var permissionRequestCount = 0

        composeRule.setContent {
            MuxTvTheme {
                AddSourceRoute(
                    onboarding = onboarding,
                    onCompleted = {},
                    onBack = {},
                    requestLocalNetworkPermission = {
                        permissionRequestCount += 1
                        LocalNetworkPermissionOutcome.GRANTED
                    },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("source-name").assertIsFocused()
        composeRule.onNodeWithTag("source-name").performTextInput("Локальный IPTV")
        composeRule.onNodeWithTag("source-name").press(Key.DirectionDown)

        composeRule.onNodeWithTag("source-locator").assertIsFocused()
        composeRule.onNodeWithTag("source-locator")
            .performSemanticsAction(SemanticsActions.SetText) { action ->
                action(AnnotatedString(locator))
            }
        composeRule.onNodeWithTag("source-locator").press(Key.DirectionDown)

        composeRule.onNodeWithText("Показать временно")
            .assertIsFocused()
            .press(Key.DirectionRight)
        composeRule.onNodeWithText("Проверить")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("source-local-network-action")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("source-local-network-action")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("source-http-cancel").fetchSemanticsNodes().size == 1
        }
        composeRule.runOnIdle {
            check(permissionRequestCount == 1)
            check(
                onboarding.prepareCalls == listOf(
                    PrepareCall(locator, insecureHttpApproved = false),
                    PrepareCall(locator, insecureHttpApproved = false),
                ),
            )
        }

        composeRule.onNodeWithTag("source-http-cancel")
            .assertIsFocused()
            .press(Key.DirectionLeft)
        composeRule.onNodeWithText("Разрешить HTTP")
            .assertIsFocused()
            .press(Key.Enter)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("source-confirm").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Подтвердите адрес: http://192.168.1.20:8080")
            .assertExists()
        composeRule.runOnIdle {
            check(
                onboarding.prepareCalls == listOf(
                    PrepareCall(locator, insecureHttpApproved = false),
                    PrepareCall(locator, insecureHttpApproved = false),
                    PrepareCall(locator, insecureHttpApproved = true),
                ),
            )
        }
    }
}

private data class PrepareCall(
    val locator: String,
    val insecureHttpApproved: Boolean,
)

private class LanThenHttpOnboarding(
    private val expectedLocator: String,
) : SourceOnboarding {
    val prepareCalls = mutableListOf<PrepareCall>()
    private val handle = LocalJourneyPreparationHandle()

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult {
        check(locator == expectedLocator)
        prepareCalls += PrepareCall(locator, insecureHttpApproved)
        return when (prepareCalls.size) {
            1 -> SourcePreparationResult.LocalNetworkAccessRequired
            2 -> SourcePreparationResult.InsecureTransportApprovalRequired
            3 -> {
                check(insecureHttpApproved)
                SourcePreparationResult.Prepared(
                    handle = handle,
                    displayEndpoint = "http://192.168.1.20:8080",
                )
            }

            else -> error("Unexpected preparation attempt ${prepareCalls.size}")
        }
    }

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

private class LocalJourneyPreparationHandle : SourcePreparationHandle()

private fun SemanticsNodeInteraction.press(
    key: Key,
): SemanticsNodeInteraction = apply {
    performKeyInput {
        keyDown(key)
        keyUp(key)
    }
}

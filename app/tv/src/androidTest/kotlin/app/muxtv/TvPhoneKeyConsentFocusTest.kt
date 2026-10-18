package app.muxtv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.localcontrol.TvPhoneKeyConsentActions
import org.junit.Rule
import org.junit.Test

/**
 * Pure on-device TV D-pad consent contract. The independent full QR/activity lifecycle
 * is qualified by TV QR runtime/device tests and the build-logic integration contract.
 * No network or real phone hardware is involved.
 */
class TvPhoneKeyConsentFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pendingPhoneDefaultsToDenyAndDpadRejectRevokesOffer() {
        val fingerprint = "ab".repeat(32)
        var requested by mutableStateOf<String?>(fingerprint)
        var denied = 0
        var approved = 0
        rule.setContent {
            MuxTvTheme {
                TvPhoneKeyConsentActions(
                    fingerprint = requested,
                    approved = false,
                    onApprove = { approved++ },
                    onDeny = {
                        denied++
                        if (it == requested) requested = null
                    },
                )
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("local-tv-phone-consent-deny")
                .fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("local-tv-phone-consent-fingerprint").assertExists()
        rule.onNodeWithTag("local-tv-phone-consent-deny")
            .assertIsFocused()
            .performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        rule.runOnIdle {
            check(denied == 1) { "D-pad did not invoke Reject exactly once." }
            check(approved == 0) { "Approval was invoked without a positive TV action." }
            check(requested == null) { "Rejected fingerprint was not revoked." }
        }
        rule.onNodeWithTag("local-tv-phone-consent-approve").assertDoesNotExist()
    }

    @Test fun absentCandidateDoesNotRenderApprovalOrDeny() {
        var requested by mutableStateOf<String?>(null)
        rule.setContent {
            MuxTvTheme {
                TvPhoneKeyConsentActions(
                    fingerprint = requested,
                    approved = false,
                    onApprove = { error("No candidate may be approved") },
                    onDeny = { error("No candidate may be denied") },
                )
            }
        }
        rule.onNodeWithTag("local-tv-phone-consent-approve").assertDoesNotExist()
        rule.onNodeWithTag("local-tv-phone-consent-deny").assertDoesNotExist()
        rule.runOnIdle { requested = "cd".repeat(32) }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("local-tv-phone-consent-deny")
                .fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("local-tv-phone-consent-deny").assertIsFocused()
        rule.runOnIdle { requested = null }
        rule.onNodeWithTag("local-tv-phone-consent-approve").assertDoesNotExist()
    }

    @Test fun explicitApprovalDoesNotAutoGrantAndShowsWaitingForProof() {
        val fingerprint = "ef".repeat(32)
        var requested by mutableStateOf<String?>(fingerprint)
        var approved by mutableStateOf(false)
        var approvals = 0
        rule.setContent {
            MuxTvTheme {
                TvPhoneKeyConsentActions(
                    fingerprint = requested,
                    approved = approved,
                    onApprove = {
                        approvals++
                        approved = (it == fingerprint)
                    },
                    onDeny = { requested = null },
                )
            }
        }
        rule.onNodeWithTag("local-tv-phone-consent-deny").assertIsFocused()
        rule.runOnIdle {
            check(approvals == 0 && !approved) { "Automatic TV approval is forbidden." }
        }
        // androidx.tv.material3.Button requires TV focus for D-pad activation.
        // Semantics performClick() on an unfocused TV control is NOT a real remote action.
        rule.onNodeWithTag("local-tv-phone-consent-deny").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        rule.onNodeWithTag("local-tv-phone-consent-approve").assertIsFocused()
            .performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        rule.runOnIdle {
            check(approvals == 1 && approved) { "D-pad approve did not invoke exactly once." }
        }
        rule.onNodeWithTag("local-tv-phone-consent-approve").assertDoesNotExist()
        rule.onNodeWithTag("local-tv-phone-consent-deny").assertDoesNotExist()
    }
}

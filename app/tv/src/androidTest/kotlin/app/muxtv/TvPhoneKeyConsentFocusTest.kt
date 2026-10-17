package app.muxtv

import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.localcontrol.PhonePairingRoute
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import org.junit.Rule
import org.junit.Test

/** Emulator-only UI integration. The pending phone request is a test-injected public fingerprint. */
class TvPhoneKeyConsentFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pendingPhoneDefaultsToDenyAndDpadRejectRevokesQr() {
        var session: TvHpkeQrSession? = null
        var displayed = false
        var denied = 0
        var approved = 0
        val fingerprint = "ab".repeat(32)
        rule.setContent {
            MuxTvTheme {
                PhonePairingRoute(
                    onStart = { null },
                    onStop = { session?.close() },
                    onStartPublicKeyPreview = {
                        val now = SystemClock.elapsedRealtime()
                        TvHpkeQrSession.create(now).also { session = it }
                            .presentation(now)
                    },
                    onRevokePublicKeyPreview = { session?.close() },
                    publicKeyPreviewIsActive = {
                        session?.presentation(SystemClock.elapsedRealtime()) != null
                    },
                    pendingPhoneKeyFingerprint = { if (displayed) fingerprint else null },
                    approvePhoneKeyOnTv = { approved++; true },
                    denyPhoneKeyOnTv = {
                        denied++
                        if (it == fingerprint) {
                            session?.close()
                            true
                        } else {
                            false
                        }
                    },
                    pendingFromTv = { null },
                    approveFromTv = { false },
                    denyFromTv = { false },
                    pairedCount = { 0 },
                    connectionIsActive = { false },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("local-tv-key-open").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("local-tv-key-qr").fetchSemanticsNodes().size == 1
        }
        displayed = true
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("local-tv-phone-consent-deny").fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("local-tv-phone-consent-fingerprint").assertExists()
        rule.onNodeWithTag("local-tv-phone-consent-deny")
            .assertIsFocused().performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        rule.runOnIdle {
            check(denied == 1) { "Expected one D-pad rejection, got $denied" }
            check(approved == 0) { "TV key was auto-approved" }
            check(session?.presentation(SystemClock.elapsedRealtime()) == null) {
                "Deny failed to revoke the TV QR owner in the fixture"
            }
        }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("local-tv-phone-consent-approve").fetchSemanticsNodes().isEmpty()
        }
        session?.close()
    }

    @Test fun fingerprintNotPendingNeverRendersAnApprovalAction() {
        var session: TvHpkeQrSession? = null
        rule.setContent {
            MuxTvTheme {
                PhonePairingRoute(
                    onStart = { null },
                    onStop = { session?.close() },
                    onStartPublicKeyPreview = {
                        val now = SystemClock.elapsedRealtime()
                        TvHpkeQrSession.create(now).also { session = it }.presentation(now)
                    },
                    onRevokePublicKeyPreview = { session?.close() },
                    publicKeyPreviewIsActive = {
                        session?.presentation(SystemClock.elapsedRealtime()) != null
                    },
                    pendingPhoneKeyFingerprint = { null },
                    approvePhoneKeyOnTv = { error("Consent must not be reachable") },
                    denyPhoneKeyOnTv = { error("No candidate") },
                    pendingFromTv = { null },
                    approveFromTv = { false },
                    denyFromTv = { false },
                    pairedCount = { 0 },
                    connectionIsActive = { false },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("local-tv-key-open").performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("local-tv-key-qr").fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("local-tv-phone-consent-approve").assertDoesNotExist()
        session?.close()
    }
}

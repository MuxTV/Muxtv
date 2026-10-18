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
import app.muxtv.localcontrol.tvqr.TvHpkeQrPresentation
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test

/** Emulator-only UI integration. The pending phone request is a test-injected public fingerprint. */
class TvPhoneKeyConsentFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pendingPhoneDefaultsToDenyAndDpadRejectRevokesQr() {
        val active = AtomicBoolean(false)
        var displayed = false
        var denied = 0
        var approved = 0
        val fingerprint = "ab".repeat(32)
        rule.setContent {
            MuxTvTheme {
                PhonePairingRoute(
                    onStart = { null },
                    onStop = { active.set(false) },
                    onStartPublicKeyPreview = {
                        active.set(true)
                        syntheticPublicPreview()
                    },
                    onRevokePublicKeyPreview = { active.set(false) },
                    publicKeyPreviewIsActive = { active.get() },
                    pendingPhoneKeyFingerprint = { if (displayed) fingerprint else null },
                    approvePhoneKeyOnTv = { approved++; true },
                    denyPhoneKeyOnTv = {
                        denied++
                        if (it == fingerprint) {
                            active.set(false)
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
            check(!active.get()) {
                "Deny failed to revoke the synthetic TV QR session"
            }
        }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("local-tv-phone-consent-approve").fetchSemanticsNodes().isEmpty()
        }
        active.set(false)
    }

    @Test fun fingerprintNotPendingNeverRendersAnApprovalAction() {
        val active = AtomicBoolean(false)
        rule.setContent {
            MuxTvTheme {
                PhonePairingRoute(
                    onStart = { null },
                    onStop = { active.set(false) },
                    onStartPublicKeyPreview = {
                        active.set(true)
                        syntheticPublicPreview()
                    },
                    onRevokePublicKeyPreview = { active.set(false) },
                    publicKeyPreviewIsActive = { active.get() },
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
        active.set(false)
    }
    /**
     * UI-only fixture. Real Tink keypair generation and public-only serialization are
     * tested independently in :local-control:crypto-android-compat on both canonical AVDs.
     * This prevents provider warmup from hiding a real Compose focus defect.
     */
    private fun syntheticPublicPreview() = TvHpkeQrPresentation(
        qrValue = "MXLC04:1:" + "1".repeat(32) + ":" + "2".repeat(32) + ":PUBLIC_UI_FIXTURE",
        fingerprintSha256 = "a".repeat(64),
        expiresAtElapsedMillis = SystemClock.elapsedRealtime() + 120_000,
    )

}

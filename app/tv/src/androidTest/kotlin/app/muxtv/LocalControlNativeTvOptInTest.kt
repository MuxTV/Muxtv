package app.muxtv

import android.os.SystemClock
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
import app.muxtv.localcontrol.PhonePairingRoute
import app.muxtv.localcontrol.TvPairingController
import app.muxtv.localcontrol.tvqr.TvHpkeQrPresentation
import org.junit.Rule
import org.junit.Test

/**
 * Hosted Android TV D-pad UI contract: a visible key preview never opens a
 * native listener implicitly. Network I/O is provided by the Activity owner
 * and qualified independently; these callbacks intentionally do not bind.
 */
class LocalControlNativeTvOptInTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tvExplicitDpadActionAloneEnablesAndDisposingRevokesNativeEndpoint() {
        var screenVisible by mutableStateOf(true)
        var nativeStarts = 0
        var legacyHttpStarts = 0
        var revocations = 0
        val expires = SystemClock.elapsedRealtime() + 120_000L
        rule.setContent {
            MuxTvTheme {
                if (screenVisible) {
                    PhonePairingRoute(
                        onStart = { legacyHttpStarts++; null },
                        onStop = {},
                        onStartPublicKeyPreview = {
                            TvHpkeQrPresentation(
                                "MXLC04:1:TV_PUBLIC_ONLY_TEST_QR",
                                "ab".repeat(32),
                                expires,
                            )
                        },
                        onEnableNativeProbe = {
                            nativeStarts++
                            TvPairingController.NativeProbeEndpoint("192.168.1.2", 43210)
                        },
                        onRevokePublicKeyPreview = { revocations++ },
                        publicKeyPreviewIsActive = { true },
                        pendingPhoneKeyFingerprint = { null },
                        approvePhoneKeyOnTv = { false },
                        denyPhoneKeyOnTv = { false },
                        pendingFromTv = { null },
                        approveFromTv = { false },
                        denyFromTv = { false },
                        pairedCount = { 0 },
                        connectionIsActive = { false },
                        onBack = {},
                    )
                }
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("local-tv-key-open").fetchSemanticsNodes().size == 1
        }
        rule.runOnIdle {
            check(legacyHttpStarts == 0) { "Entering phone pairing opened HTTP implicitly." }
            check(nativeStarts == 0) { "Entering phone pairing opened TCP implicitly." }
        }
        rule.onNodeWithTag("local-pair-close").assertIsFocused()
            .performKeyInput {
                keyDown(Key.DirectionUp)
                keyUp(Key.DirectionUp)
            }
        rule.onNodeWithTag("local-tv-key-open").assertIsFocused()
            .performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("local-tv-key-back").fetchSemanticsNodes().size == 1 &&
                rule.onAllNodesWithTag("local-tv-native-opt-in")
                    .fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithTag("local-tv-key-back").assertIsFocused()
        rule.runOnIdle { check(nativeStarts == 0) { "QR preview silently opened TCP." } }
        rule.onNodeWithTag("local-tv-key-back").performKeyInput {
            keyDown(Key.DirectionUp)
            keyUp(Key.DirectionUp)
        }
        rule.onNodeWithTag("local-tv-key-renew").assertIsFocused()
            .performKeyInput {
                keyDown(Key.DirectionUp)
                keyUp(Key.DirectionUp)
            }
        rule.onNodeWithTag("local-tv-native-opt-in").assertIsFocused()
            .performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("local-tv-native-endpoint").fetchSemanticsNodes().size == 1
        }
        rule.runOnIdle {
            check(nativeStarts == 1) { "Native transport must start on exactly one TV action." }
            screenVisible = false
        }
        rule.waitForIdle()
        rule.runOnIdle { check(revocations >= 2) { "Route removal did not revoke the QR." } }
    }
}

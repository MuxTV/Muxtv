package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TV-owned lab may prove key agreement only; installed Android APKs, native
 * TCP, legacy pair endpoints and IPTV sources must remain disconnected.
 */
class Lc04MxH1TvOwnedHandshakeIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `TV-owned owner has no Android or source authority and retains real consent gate`() {
        val gradle = root.resolve("local-control/mxh1-session-lab/build.gradle.kts")
            .readText()
        for (required in listOf(
            """:local-control:wire-lab""", """:local-control:qr-public""",
            """:local-control:enrollment""", """:local-control:protected-channel""",
            "implementation(libs.tink)", "testImplementation(libs.junit)",
        )) assertContains(gradle, required)
        for (blocked in listOf(":catalog:", ":core:credentials", ":local-control:onboarding",
            "libs.okhttp", "libs.ktor", "androidx.", "com.android.")) {
            assertFalse(gradle.contains(blocked), blocked)
        }
        val tv = root.resolve(
            "local-control/mxh1-session-lab/src/main/kotlin/" +
                "app/muxtv/localcontrol/mxh1/Lc04MxH1TvOwnerLab.kt",
        ).readText()
        for (required in listOf(
            "TvPhoneEnrollment", "VerifiedPhoneIdentity", "activateApprovedPhone(",
            "gate.verifiedFingerprintForTv()", "verifier.verify(signature, statement)",
            "Lc04MxH1HpkeContext.forOffer(frame)",
            "HybridDecrypt::class.java", "Lc04SyntheticHandoffKeySchedule.derive(",
            "Lc04HandoffKind.TV_KEY_CONFIRM", "Lc04HandoffKind.PHONE_KEY_CONFIRM",
            "keys?.verifySyntheticPhoneConfirmation(tag)", "privateReceiver = null",
            "phase = Phase.REVOKED", "TTL_MILLIS = 120_000L",
        )) assertContains(tv, required)
        val phone = root.resolve(
            "local-control/mxh1-session-lab/src/main/kotlin/" +
                "app/muxtv/localcontrol/mxh1/Lc04MxH1PhoneLab.kt",
        ).readText()
        for (required in listOf("signMxH1SyntheticSeedOffer(",
            "Lc04MxH1HpkeContext.forOffer(", "verifySyntheticTvConfirmation(tvTag)",
            "Lc04HandoffKind.PHONE_KEY_CONFIRM", "pendingSeed?.fill(0)",
            "signingPhone.close()", "phase = Phase.REVOKED")) {
            assertContains(phone, required)
        }
        for (src in listOf(tv, phone)) {
            for (blocked in listOf("SourceOnboarding", "SourcePreparationHandle",
                "SourcePreparationRequest", "HttpClient", "Socket(", "SharedPreferences",
                "android.content.", "M3u(", "Xtream(", "PasswordStore")) {
                assertFalse(src.contains(blocked), "Unexpected source authority: $blocked")
            }
        }
    }

    @Test fun `old MXW1 and Android app must not gain MXH1 route or handshake imports`() {
        val settings = root.resolve("settings.gradle.kts").readText()
        assertContains(settings, """:local-control:mxh1-session-lab""")
        val manifest = root.resolve(".work/meta/modules.yaml").readText()
        assertContains(manifest, "local-control-mxh1-session-lab:")
        for (app in listOf("app/phone/build.gradle.kts", "app/tv/build.gradle.kts",
            "local-control/native-transport/build.gradle.kts",
            "local-control/server/build.gradle.kts")) {
            assertFalse(root.resolve(app).readText().contains(":local-control:mxh1-session-lab"))
        }
        val native = root.resolve("local-control/native-transport/src/main/kotlin/" +
            "app/muxtv/localcontrol/nativetransport/Lc04NativeTcpTransport.kt").readText()
        assertFalse(native.contains("Lc04MxH1"))
        assertFalse(native.contains("SIGNED_SEED_OFFER"))
        val old = root.resolve("local-control/wire-lab/src/main/kotlin/" +
            "app/muxtv/localcontrol/wirelab/Lc04WireLab.kt").readText()
        assertFalse(old.contains("SIGNED_SEED_OFFER"))
        val hpke = root.resolve("local-control/wire-lab/src/main/kotlin/" +
            "app/muxtv/localcontrol/wirelab/Lc04MxH1HpkeContext.kt").readText()
        assertContains(hpke, "MuxTV-LC04-MXH1-HPKE-SEED-HANDOFF-v1")
        assertFalse(hpke.contains("forProbe("))
    }

    @Test fun `exact HEAD hosted JVM security gate includes hostile tests and evidence`() {
        val tests = root.resolve(
            "local-control/mxh1-session-lab/src/test/kotlin/" +
                "app/muxtv/localcontrol/mxh1/Lc04MxH1TvOwnerLabTest.kt",
        ).readText()
        for (test in listOf(
            "exactQrTvLocalConsentAndMutualHmacConfirmOnlyOneSyntheticSession",
            "tvRejectsSeedOfferBeforeItsOwnTvLocalApprovedEd25519Identity",
            "forgedTvConfirmationWithCorrectQrIdsAndDigestFailsActualKeyMac",
            "forgedPhoneConfirmationWithCorrectOfferDigestFailsActualKeyMac",
            "changedSignedSeedOfferIdentitiesCiphertextAndSignatureFailClosed",
            "wrongHpkeInfoEvenWithValidSignedOfferCannotDecryptOnTv",
            "wrongPhoneIdentityCannotUseAnotherApprovedPhoneFingerprint",
            "replayOfSignedOfferBeforePhoneConfirmationRevokesTvOwner",
            "replayOfPhoneConfirmationAfterSuccessfulHandshakeRevokes",
            "wrongCounterAndForeignConfirmationCannotAdvanceEitherState",
            "expiryClockRollbackAndExplicitStopErasePendingState",
            "malformedAndMxw1MessagesHaveNoAuthPath",
        )) assertContains(tests, test)
        val wf = root.resolve(".github/workflows/lc04-mxh1-tv-owned-handshake-ci.yml").readText()
        assertContains(wf, "runs-on: ubuntu-latest")
        assertContains(wf, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(wf, ":local-control:mxh1-session-lab:test")
        assertContains(wf, "Lc04MxH1TvOwnedHandshakeIsolationTest")
        assertContains(wf, "local-control/mxh1-session-lab/build/test-results/test/**")
        assertFalse(wf.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-tv-owned-handshake-20261105.md",
        ).isFile)
    }
}

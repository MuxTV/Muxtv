package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Research proof must reuse the installed TV QR owner, never mint authority. */
class Lc04MxH1OriginalTvQrOwnerContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `MXH1 is on original TV-owned HPKE and approved phone, not a second key owner`() {
        val tv = root.resolve(
            "local-control/tv-qr/src/main/kotlin/" +
                "app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt",
        ).readText()
        for (token in listOf(
            "privateReceiver: KeysetHandle?",
            "pendingPhoneEnrollment: TvPhoneEnrollment?",
            "approvedPhoneVerifier: PublicKeyVerify?",
            "pendingPhoneEnrollment?.verifiedFingerprintForTv() != approved",
            "acceptSyntheticMxH1SeedOffer(",
            "acceptSyntheticMxH1PhoneConfirmation(",
            "hasSyntheticMxH1Proof(",
            "verifier.verify(signature, statement)",
            "Lc04MxH1HpkeContext.forOffer(frame)",
            "HybridDecrypt::class.java",
            "Lc04SyntheticHandoffKeySchedule.derive(seed, transcript)",
            "Lc04HandoffKind.TV_KEY_CONFIRM",
            "Lc04HandoffKind.PHONE_KEY_CONFIRM",
            "mxh1Keys?.verifySyntheticPhoneConfirmation(tag)",
            "mxh1Keys?.close()",
            "mxh1Phase = MxH1Phase.REVOKED",
            "privateReceiver = null",
        )) assertContains(tv, token)
        // Existing legacy QR and proof functionality must retain their own
        // method names and must not dispatch MXH1 over the old TCP route.
        assertContains(tv, "fun acceptProtectedProbe(")
        assertContains(tv, "fun activateVerifiedPhone(")
        val native = root.resolve(
            "local-control/native-transport/src/main/kotlin/" +
                "app/muxtv/localcontrol/nativetransport/Lc04NativeTcpTransport.kt",
        ).readText()
        assertFalse(native.contains("MxH1"))
        assertFalse(native.contains("SIGNED_SEED_OFFER"))
        val controller = root.resolve(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt",
        ).readText()
        assertContains(controller, "publicQrSession = receiver")
        assertContains(controller, "nativeListener.close()")
        assertContains(controller, "publicQrSession?.close()")
        assertFalse(controller.contains("Lc04MxH1TvOwnerLab"))
    }

    @Test fun `only synthetic keys may be referenced without a data transport`() {
        val gradle = root.resolve("local-control/tv-qr/build.gradle.kts").readText()
        assertContains(gradle, """implementation(project(":local-control:protected-channel"))""")
        assertContains(gradle, """testImplementation(project(":local-control:mxh1-session-lab"))""")
        assertFalse(gradle.contains("""implementation(project(":local-control:mxh1-session-lab"))"""))
        for (bad in listOf(
            ":catalog:", ":core:credentials", ":local-control:onboarding",
            "libs.okhttp", "libs.ktor",
        )) assertFalse(gradle.contains(bad), bad)
        val source = root.resolve("local-control/tv-qr/src/main/kotlin/" +
            "app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt").readText()
        for (bad in listOf("SourcePreparationHandle", "SourcePreparationRequest",
            "HttpClient", "Socket(", "SharedPreferences", "MxH1Tcp",
            "getUsername(", "getPassword(")) {
            assertFalse(source.contains(bad), bad)
        }
        for (app in listOf("app/tv/build.gradle.kts", "app/phone/build.gradle.kts",
            "local-control/native-transport/build.gradle.kts")) {
            assertFalse(root.resolve(app).readText().contains(
                ":local-control:mxh1-session-lab",
            ))
        }
    }

    @Test fun `real TV original-owner hostile suite and exact HEAD hosted evidence required`() {
        val test = root.resolve("local-control/tv-qr/src/test/kotlin/" +
            "app/muxtv/localcontrol/tvqr/TvOriginalMxH1OwnerTest.kt").readText()
        for (case in listOf(
            "realExistingQrOwnerAcceptsTvApprovedPhoneAndCompletesBothHmacProofs",
            "mxh1OfferBeforeExactTvConsentRevokesOriginalQrAndGate",
            "forgedTvMacWithCorrectSignedOfferDigestCannotBeAcceptedByPhone",
            "forgedPhoneMacAndReplayRevokeOriginalQrKeyOwner",
            "exactOfferReplayDuringOutstandingConfirmationRevokesOriginalOwner",
            "duplicatePhoneConfirmationAfterSuccessClosesPreviouslyVerifiedSession",
            "tamperedSignedOfferHeaderCiphertextAndSignatureRevokeSameOwner",
            "domainSeparatedHpkeSeedUnderWrongContextCannotDecrypt",
            "oldMxw1ProbeCannotCoexistAfterMxH1HasStarted",
            "originalActivityStopCloseAndExpiryEraseMxH1State",
        )) assertContains(test, case)
        val workflow = root.resolve(
            ".github/workflows/lc04-original-tv-mxh1-qr-owner-ci.yml",
        ).readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:tv-qr:test")
        assertContains(workflow, "Lc04MxH1OriginalTvQrOwnerContractTest")
        assertContains(workflow, "local-control/tv-qr/build/test-results/test/**")
        assertFalse(workflow.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-existing-tv-qr-owner-20261105.md",
        ).isFile)
    }
}

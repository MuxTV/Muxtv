package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reject accidental authorization of legacy plaintext pairing or provider source handling
 * from the not-yet-networked LC04 Ed25519 proof-of-possession building block.
 */
class LocalControlPhoneEnrollmentIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `enrollment module has no source capability or transport dependencies`() {
        val gradle = root.resolve("local-control/enrollment/build.gradle.kts").readText()
        assertContains(gradle, """project(":local-control:qr-public")""")
        assertContains(gradle, "libs.tink")
        for (forbidden in listOf(
            ":local-control:server", ":local-control:onboarding", ":local-control:core",
            ":catalog:", ":core:credentials", "libs.ktor", "libs.okhttp",
        )) assertFalse(gradle.contains(forbidden), "Forbidden enrollment dependency: $forbidden")

        val file = root.resolve(
            "local-control/enrollment/src/main/kotlin/app/muxtv/localcontrol/enrollment/TvPhoneEnrollment.kt",
        ).readText()
        assertContains(file, "approveOnTv(")
        assertContains(file, "tvReceiverIsAlive")
        assertContains(file, "PhoneEnrollmentDenial.TV_CONSENT_REQUIRED")
        assertContains(file, "PublicKeyVerify")
        assertContains(file, "SignatureConfig.register()")
        assertContains(file, "MuxTV-LC04-PHONE-ENROLL-v1")
        for (forbidden in listOf(
            "SourceOnboarding", "SourcePreparationHandle", "PairingGrant",
            "PairingCapability.SOURCE_CREATE", "HttpClient", "TvLanServer", "route(", "post(",
        )) assertFalse(file.contains(forbidden), "Forbidden enrollment authority: $forbidden")
    }

    @Test fun `the legacy LAN transport cannot import enrollment or source credential handler`() {
        val server = root.resolve("local-control/server/src/main")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(server.isNotEmpty())
        for (file in server) {
            val text = file.readText()
            assertFalse(text.contains("localcontrol.enrollment"), file.path)
            assertFalse(text.contains("localcontrol.onboarding"), file.path)
            assertFalse(text.contains("TvPhoneEnrollment"), file.path)
        }
        val tv = root.resolve(
            "local-control/tv-qr/src/main/kotlin/app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt",
        ).readText()
        assertContains(tv, "phoneEnrollmentGate(")
        assertContains(tv, "pendingPhoneEnrollment?.close()")
    }

    @Test fun `proof CI uses exact tested source and only hosted runners`() {
        val workflow = root.resolve(".github/workflows/local-control-phone-enrollment.yml").readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:enrollment:test")
        assertContains(workflow, ":app:tv:compileDebugKotlin")
        assertFalse(workflow.contains("self-hosted"))
        assertFalse(workflow.contains("android-emulator-runner"))
    }

    @Test fun `tv local consent requires visible fingerprint and reject is focus default`() {
        val route = root.resolve(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/PhonePairingRoute.kt",
        ).readText()
        assertContains(route, "pendingPhoneKeyFingerprint()")
        assertContains(route, "denyPhoneFocus.requestFocus()")
        assertContains(route, "focusRequester(denyPhoneFocus)")
        assertContains(route, "denyPhoneKeyOnTv(requested)")
        assertContains(route, "approvePhoneKeyOnTv(requested)")
        assertContains(route, "local-tv-phone-consent-fingerprint")
        val controller = root.resolve(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt",
        ).readText()
        assertContains(controller, "if (!isTvVisible()) return false")
        assertContains(controller, "gate.pendingFingerprintForTv() != exactVisibleFingerprint")
        assertContains(controller, "revokePublicKeyQrPreview()")
    }

    @Test fun `public enrollment framing cannot become a provider or HTTP transport`() {
        val wire = root.resolve(
            "local-control/enrollment/src/main/kotlin/app/muxtv/localcontrol/enrollment/Lc04PublicEnrollmentWire.kt",
        ).readText()
        assertContains(wire, "PHONE_PUBLIC_KEY")
        assertContains(wire, "TV_CHALLENGE")
        assertContains(wire, "PHONE_PROOF")
        assertContains(wire, "MessageDigest.isEqual")
        for (forbidden in listOf(
            "SourceOnboarding", "SOURCE_PREPARE", "SOURCE_CREATE", "SOURCE_ACTIVATE",
            "TvLanServer", "HttpClient", "post(", "route(", "INTERNET",
            "getUsername(", "getPassword(", "providerUrl",
        )) {
            assertFalse(wire.contains(forbidden), "Unauthorized public enrollment capability: $forbidden")
        }
        val gate = root.resolve(
            "local-control/enrollment/src/main/kotlin/app/muxtv/localcontrol/enrollment/TvPhoneEnrollment.kt",
        ).readText()
        assertContains(gate, "beginPublicOffer(")
        assertContains(gate, "verifyPublicProof(")
        assertContains(gate, "return verify(signed)")
        assertContains(gate, "begin(public)")
    }

    @Test fun `native protected probe verifies enrolled phone before HPKE decrypt and cannot reach legacy server`() {
        val tv = root.resolve(
            "local-control/tv-qr/src/main/kotlin/app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt",
        ).readText()
        assertContains(tv, "gate.verifiedFingerprintForTv() != identity.fingerprint")
        assertContains(tv, "approvedPhoneVerifier")
        assertContains(tv, "verifier.verify(signature, statement)")
        assertContains(tv, "lastVerifiedProbeCounter")
        assertContains(tv, "Lc04TypedTranscript.originalSignedRequestDigest(frame)")
        val signatureVerify = tv.indexOf("verifier.verify(signature, statement)")
        val decrypt = tv.indexOf(".decrypt(cipher, contextInfo)")
        assertTrue(signatureVerify >= 0 && decrypt > signatureVerify,
            "TV must verify the enrolled Ed25519 signer before HPKE private decryption")

        val phone = root.resolve(
            "local-control/enrollment/src/main/kotlin/app/muxtv/localcontrol/enrollment/PhoneProtectedProbe.kt",
        ).readText()
        assertContains(phone, "HybridEncrypt::class.java")
        assertContains(phone, "signingPhone.signProtectedProbe(")
        assertContains(phone, "Lc04OneShotReplyGuard(")
        for (forbidden in listOf(
            "HttpClient", "TvLanServer", "SourceOnboarding", "SOURCE_CREATE",
            "SOURCE_PREPARE", "SOURCE_ACTIVATE", "post(", "route(", "getPassword(",
        )) {
            assertFalse(phone.contains(forbidden), "Protected probe must have no network or source authority: $forbidden")
        }

        val server = root.resolve("local-control/server/src/main")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (file in server) {
            val code = file.readText()
            assertFalse(code.contains("acceptProtectedProbe"), file.path)
            assertFalse(code.contains("PhoneProtectedProbeFactory"), file.path)
            assertFalse(code.contains("activateVerifiedPhone"), file.path)
        }
    }
}

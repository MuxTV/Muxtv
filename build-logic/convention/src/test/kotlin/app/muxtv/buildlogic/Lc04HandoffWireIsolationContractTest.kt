package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A canonical handshake frame by itself carries no authenticated authority. */
class Lc04HandoffWireIsolationContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `MXH1 handshake kind must be isolated from deployed MXW1 and Android sockets`() {
        val lab = root.resolve(
            "local-control/wire-lab/src/main/kotlin/" +
                "app/muxtv/localcontrol/wirelab/Lc04HandoffWireLab.kt",
        ).readText()
        for (required in listOf(
            "MXH1", "private const val VERSION = 1",
            "SIGNED_SEED_OFFER", "TV_KEY_CONFIRM", "PHONE_KEY_CONFIRM",
            "const val HEADER_BYTES = 138", "const val MAX_PAYLOAD_BYTES = 2120",
            "offerSigningStatement", "digestOfSignedOffer",
            "structurallyMatchesOffer", "MessageDigest.isEqual",
            "decodeSignedOfferBody", "signature.size == 64 || signature.size == 69",
            "digest.all { it == 0.toByte() }",
        )) assertContains(lab, required)

        for (blocked in listOf(
            "SourceOnboarding", "SourcePreparationHandle", "SourcePreparationRequest",
            "M3u(", "Xtream(", "Socket(", "HttpClient",
            "android.content.", "SharedPreferences", "KeysetHandle",
            "TinkProtoKeysetFormat", "SecretKeySpec", "Cipher.getInstance",
        )) assertFalse(lab.contains(blocked), "MXH1 canonical parser must not gain $blocked")

        val old = root.resolve(
            "local-control/wire-lab/src/main/kotlin/" +
                "app/muxtv/localcontrol/wirelab/Lc04WireLab.kt",
        ).readText()
        for (newKind in listOf("SIGNED_SEED_OFFER", "TV_KEY_CONFIRM", "PHONE_KEY_CONFIRM")) {
            assertFalse(old.contains(newKind))
        }
        val native = root.resolve(
            "local-control/native-transport/src/main/kotlin/" +
                "app/muxtv/localcontrol/nativetransport/Lc04NativeTcpTransport.kt",
        ).readText()
        assertFalse(native.contains("Lc04HandoffWireLab"))
        assertFalse(native.contains("SIGNED_SEED_OFFER"))
        for (gradle in listOf("app/tv/build.gradle.kts",
            "app/phone/build.gradle.kts", "local-control/server/build.gradle.kts",
            "local-control/onboarding/build.gradle.kts")) {
            assertFalse(root.resolve(gradle).readText().contains(
                ":local-control:protected-channel",
            ))
        }
    }

    @Test fun `hosted real Tink signature and hostile wire corpus are required`() {
        val gradle = root.resolve("local-control/wire-lab/build.gradle.kts").readText()
        assertContains(gradle, "testImplementation(libs.tink)")
        assertFalse(gradle.contains("implementation(libs.tink)"))

        val tests = root.resolve(
            "local-control/wire-lab/src/test/kotlin/" +
                "app/muxtv/localcontrol/wirelab/Lc04HandoffWireLabTest.kt",
        ).readText()
        for (scenario in listOf(
            "canonicalMaxAndMinOfferAndConfirmBoundariesRoundTrip",
            "separateVersionedHandshakeCanNeverBeMisparsedAsOldMxw1",
            "signatureStatementBindsAllPublicFieldsAndSignatureWidthButNotSignatureBytes",
            "realTinkEd25519CannotVerifyTamperedExactMxH1OfferStatement",
            "exactSignedOfferDigestCounterAndAllIdentitiesBindEachConfirmation",
            "structurallyMatchingForgedMacIsNotAuthentication",
            "untrustedTruncationGarbageVersionsKindsAndLengthMutationsAreRejected",
            "canonicalInnerCipherAndSignatureLengthsRejectForgedLayouts",
            "clonedPayloadsAndTextNeverExposePrivateBytes",
            "encodedConfirmationNonceAndMacSizesAreStrict",
        )) assertContains(tests, scenario)

        val workflow = root.resolve(
            ".github/workflows/lc04-mxh1-handshake-wire-ci.yml",
        ).readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:wire-lab:test")
        assertContains(workflow, "Lc04HandoffWireIsolationContractTest")
        assertContains(workflow, "local-control/wire-lab/build/test-results/test/**")
        assertFalse(workflow.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-handshake-wire-20261105.md",
        ).isFile)
    }
}

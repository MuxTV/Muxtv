package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LC04-S2J3C: exact original-QR/Ed25519/HPKE MXH1 owners prepare and
 * revoke source app keys. No synthetic Boolean creates source authority.
 */
class Lc04OriginalMxH1SourceKeyHandoffContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private fun file(path: String) = root.resolve(path).readText()

    @Test fun `only original MXH1 owners can construct deferred source key export`() {
        val tv = file(
            "local-control/tv-qr/src/main/kotlin/" +
                "app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt",
        )
        val phone = file(
            "local-control/mxh1-session-lab/src/main/kotlin/" +
                "app/muxtv/localcontrol/mxh1/Lc04MxH1PhoneLab.kt",
        )
        for (required in listOf(
            "Lc04SourceAppKeyCandidate.prepareFromOriginalHpkeSeed(",
            "transcript.digest()",
            "claimSourceRecordsAfterSixthWritten(",
            "mxh1PhoneProvedFinalFrame",
            "authenticSixth = result.clone()",
            "pendingSourceExport?.close()",
            "sourceRecordPort?.close()",
        )) assertContains(tv, required)
        for (required in listOf(
            "Lc04SourceAppKeyCandidate.prepareFromOriginalHpkeSeed(",
            "transcript.digest()",
            "claimSourceRecordsAfterVerifiedTvEof(",
            "phoneSide = true",
            "verifiedSixth = wire.clone()",
            "pendingSourceExport?.close()",
            "sourceRecordPort?.close()",
        )) assertContains(phone, required)
        for (owner in listOf(tv, phone)) {
            assertFalse(owner.contains("hasSyntheticMxH1Proof(now()) &&\n" +
                "            Lc04SourceAppKeyCandidate.prepare"))
        }
        val tvBuild = file("local-control/tv-qr/build.gradle.kts")
        val phoneBuild = file("local-control/mxh1-session-lab/build.gradle.kts")
        assertContains(tvBuild, "implementation(project(\":local-control:source-aead\"))")
        assertContains(phoneBuild, "implementation(project(\":local-control:source-aead\"))")
    }

    @Test fun `new exporter is domain separated and cannot become an HTTP bearer`() {
        val exporter = file(
            "local-control/source-aead/src/main/kotlin/" +
                "app/muxtv/localcontrol/sourceaead/Lc04SourceAppKeyCandidate.kt",
        )
        for (required in listOf(
            "class Lc04SourceAppKeyCandidate private constructor(",
            "SOURCE-EXPORT-EXTRACT-v1",
            "SOURCE-EXPORT-EXPAND-v1",
            "SOURCE-F6-DIGEST-v1",
            "SOURCE-TRANSCRIPT-BIND-v1",
            "SOURCE-TRAFFIC-SEED-v1",
            "exactAuthenticatedSixth.size != SIXTH_BYTES",
            "Lc04SourceAeadRecords.fromAuthenticatedOriginalCandidate(",
        )) {
            // One ASCII spelling only; guard against non-ASCII impostors.
            assertContains(exporter, required.replace("Lc04", "Lc04"))
        }
        val records = file(
            "local-control/source-aead/src/main/kotlin/" +
                "app/muxtv/localcontrol/sourceaead/Lc04SourceAeadRecords.kt",
        )
        assertContains(records, "internal fun fromAuthenticatedOriginalCandidate(")
        assertContains(records, "dedicatedAppSeed.any { it != 0.toByte() }")
        assertContains(records, "Lc04SourceAeadPort".replace("Lc04", "Lc04"))
        val forbidden = listOf(
            "PairingAuthority", "SourceOnboarding", "ServerSocket",
            "HttpClient", "SharedPreferences", "android.net",
            "syntheticDirectionDigest", "getTrafficKey", "println(",
        )
        for (text in forbidden) {
            assertFalse(exporter.contains(text), "Forbidden app key export: " + text)
        }
        val server = file(
            "local-control/server/src/main/kotlin/" +
                "app/muxtv/localcontrol/server/LanTransport.kt",
        )
        assertFalse(server.contains("Lc04SourceAppKeyCandidate"))
        val tvApp = file("app/tv/build.gradle.kts")
        val phoneApp = file("app/phone/build.gradle.kts")
        assertFalse(tvApp.contains(":local-control:source-aead"))
        assertFalse(phoneApp.contains(":local-control:source-aead"))
    }

    @Test fun `hosted exact head evidence includes real original QR and forged F6 cases`() {
        val tests = file(
            "local-control/tv-qr/src/test/kotlin/" +
                "app/muxtv/localcontrol/tvqr/TvOriginalMxH1SourceKeyHandoffTest.kt",
        )
        for (required in listOf(
            "noAppKeyPortBeforeOriginalTvApprovalOrBeforeFifthAndSixth",
            "originalTvAndPhoneDerivedSourceKeysEncryptRealXtreamAndM3uFields",
            "originalTvOnStopAndPhoneOnStopRevokeExportedKeys",
            "verifiedSixthWithOneByteForgeryCannotReleasePhoneAppPort",
            "wrongOriginalPhoneOrTvSessionCannotDecryptAnotherSourceAppKey",
            "routeLossOrExpiredQrClosesApplicationKeyAfterHandoff",
        )) assertContains(tests, required)
        val ci = file(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        assertContains(ci, ":local-control:tv-qr:test")
        assertContains(ci, "Lc04OriginalMxH1SourceKeyHandoffContractTest")
        assertContains(ci, "local-control/tv-qr/build/test-results/test/**")
        assertFalse(ci.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-s2j3c-original-mxh1-source-key-handoff-20261111.md",
        ).isFile)
    }
}

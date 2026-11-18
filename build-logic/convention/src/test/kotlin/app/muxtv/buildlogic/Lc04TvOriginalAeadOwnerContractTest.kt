package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2J3D2B1: source authorization exists only inside the original TV-owned
 * authenticated F6 APPDATA adapter, not HTTP /pair or synthetic success.
 */
class Lc04TvOriginalAeadOwnerContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private val dir = "local-control/onboarding/src/main/kotlin/app/muxtv/localcontrol/onboarding/"
    private fun src(path: String): String = root.resolve(path).readText()

    @Test fun `port identity and genuine F6 verified owner gate SourceOnboarding`() {
        val qr = src("local-control/tv-qr/src/main/kotlin/" +
            "app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt")
        assertContains(qr, "fun ownsOriginalSourceRecords(")
        assertContains(qr, "sourceRecordPort === records")
        assertContains(qr, "mxh1Phase == MxH1Phase.VERIFIED")
        val bridge = src(dir + "Lc04TvOriginalAeadCommandOwner.kt")
        val authenticated = bridge.indexOf("records.openRequest(wire)")
        val authority = bridge.indexOf("ProtectedSourcePrincipal.fromOriginalTvAeadOwner(")
        val dispatch = bridge.indexOf("handler.acceptAuthenticatedPlaintext(plain)")
        val encrypted = bridge.indexOf("records.sealReply(")
        assertTrue(authenticated > 0 && authority > authenticated &&
            dispatch > authority && encrypted > dispatch)
        for (required in listOf(
            "originalTv.ownsOriginalSourceRecords(records, current)",
            "originalTv.ownsOriginalSourceRecords(sourceRecords, clock())",
            "Lc04TvSourceCommandSession(",
            "staging = staging",
            "pendingActivationNameForTv()",
            "approvePendingActivationOnTv()",
            "command.sequence == proposed.sequence + 1L",
            "command.sourceName == proposed.name",
            "APPROVAL_TTL_MS = 15_000L",
            "consumeTvActivationApproval = ::consumeTvApproval",
            "SourceStagingResult.EMPTY",
            "discardAfterOwnerRevocation()",
            "session?.close()",
            "records.close()",
            "originalTv.close()",
        )) assertContains(bridge, required)
        for (forbidden in listOf(
            "PairingAuthority(", "PairingGrant(", "PairingCapability.",
            "HttpClient", "ServerSocket", "Socket(", "InetAddress",
            "SharedPreferences", "setProcessDefaultNetwork",
            "fun sendPlaintext(", "fun getPassword(",
        )) assertFalse(bridge.contains(forbidden), forbidden)
    }

    @Test fun `existing LC01 principal remains compatible but native TV creates no bearer`() {
        val staging = src(dir + "ProtectedSourceStaging.kt")
        assertContains(staging, "internal constructor(pairing: PairingAuthority, grant: PairingGrant)")
        assertContains(staging, "fromOriginalTvAeadOwner(")
        assertContains(staging, "tvOwnedOrPairedAuthorization()")
        assertFalse(staging.contains("public constructor(tv"))
        val onb = src("local-control/onboarding/build.gradle.kts")
        assertContains(onb, "implementation(project(\":local-control:tv-qr\"))")
        assertContains(onb, "implementation(project(\":local-control:source-aead\"))")
        assertFalse(onb.contains(":local-control:protected-channel"))
        val legacy = src(
            "local-control/server/src/main/kotlin/app/muxtv/localcontrol/server/LanTransport.kt")
        assertFalse(legacy.contains("Lc04TvOriginalAeadCommandOwner"))
        assertFalse(legacy.contains("Lc04SourceAeadPort"))
        val tvApp = src("app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt")
        assertFalse(tvApp.contains("Lc04TvOriginalAeadCommandOwner"))
    }

    @Test fun `hostile source authorization evidence and CI are mandatory`() {
        val tests = src("local-control/onboarding/src/test/kotlin/" +
            "app/muxtv/localcontrol/onboarding/Lc04TvOriginalAeadCommandOwnerTest.kt")
        for (name in listOf(
            "originalTvF6M3uPrepareAndExactTvDpadApprovalActivate",
            "xtreamPasswordIsAuthenticatedButNotEchoedToPhone",
            "diagnosticTvSuccessAndFakeAppPortCannotCreateSourcePrincipal",
            "mismatchedTvOriginalOwnerCannotUseOtherSessionsAeadPort",
            "staleApprovalAndDifferentSourceNameNeverActivate",
            "routeLossAfterPreparedSourceDeniesAndCleanupCancelsHandle",
            "replayedAeadSequenceNeverInvokesOnboardingTwice",
            "originalQrExpiryRejectsNextRealAeadRecordBeforeCatalogWrite",
            "onStopDuringDurableActivationNeverConfirmsSuccess",
        )) assertContains(tests, name)
        val workflow = src(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        assertContains(workflow, ":local-control:onboarding:test")
        assertContains(workflow, "Lc04TvOriginalAeadOwnerContractTest")
        assertContains(workflow, ":app:tv:compileDebugKotlin :app:phone:compileDebugKotlin")
        assertTrue(root.resolve(
            ".work/specifications/lc04-s2j3d2b1-tv-owned-aead-source-dispatch-20261119.md",
        ).isFile)
    }
}

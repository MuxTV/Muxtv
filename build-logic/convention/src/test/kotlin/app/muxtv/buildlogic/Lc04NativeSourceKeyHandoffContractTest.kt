package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2J3D1: real F6/TCP EOF owns the one-use source APPDATA port.
 * An authenticated record key is not a TV-approved source capability.
 */
class Lc04NativeSourceKeyHandoffContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    private fun read(path: String): String = root.resolve(path).readText()

    @Test fun `phone app keys are retained only after real sixth frame and exact EOF`() {
        val phone = read("local-control/mxh1-lan/src/main/kotlin/app/muxtv/" +
            "localcontrol/mxh1lan/Lc04MxH1LeasedSealedPhone.kt")
        val eof = phone.indexOf("authenticated = input.read() == -1")
        val detached = phone.indexOf("lease.detachHandshakeSocketAfterVerifiedTvEof()")
        val claimed = phone.indexOf("phone.claimSourceRecordsAfterVerifiedTvEof(")
        assertTrue(eof > 0 && detached > eof && claimed > detached)
        for (required in listOf(
            "completeSyntheticExchange(): Boolean = completeExchange(retainSourceKey = false)",
            "completeSourceKeyHandoffAfterTvEof()",
            "if (!sourceKeyRetained) close()",
            "lease::sourceHandoffEligible",
            "sourceRecordPort?.close()",
        )) assertContains(phone, required)
        val lease = read("local-control/mxh1-lan/src/main/kotlin/app/muxtv/" +
            "localcontrol/mxh1lan/Lc04MxH1SelectedNetworkLease.kt")
        for (required in listOf(
            "Phase.SOURCE_HANDOFF",
            "fun detachHandshakeSocketAfterVerifiedTvEof()",
            "fun sourceHandoffEligible(): Boolean {",
            "completedSocket.close()",
            "if (!retired || !sourceHandoffEligible())",
            "selectedRouteEligible()",
            "revokeOriginalQrAndKeys()",
        )) assertContains(lease, required)
        val readOnly = lease.substringAfter("fun sourceHandoffEligible(): Boolean {")
            .substringBefore("fun revokeSelectedNetwork()")
        assertFalse(readOnly.contains("\n            close()"))
        assertContains(readOnly, "selectedRouteEligible()")
        assertContains(readOnly, "phase == Phase.SOURCE_HANDOFF")
    }

    @Test fun `TV claims only after output half close and never grants a source`() {
        val tv = read("local-control/mxh1-lan/src/main/kotlin/app/muxtv/" +
            "localcontrol/mxh1lan/Lc04MxH1SealedPrivateLanTv.kt")
        val outputClosed = tv.indexOf("socket.shutdownOutput()")
        val claimed = tv.indexOf("tv.claimSourceRecordsAfterSixthWritten(")
        assertTrue(outputClosed > 0 && claimed > outputClosed)
        for (required in listOf(
            "startOnApprovedPrivateIpv4WithSourceKeyHandoff(",
            "startLoopbackWithSourceKeyHandoffForHostTests(",
            "takeSourceKeyPortAfterRealF6OutputClose()",
            "if (stopped || !completed) return null",
            "sourceRecordPort?.close()",
            "sourceRouteEligible()",
        )) assertContains(tv, required)
        val facade = read("local-control/mxh1-lan/src/main/kotlin/app/muxtv/" +
            "localcontrol/mxh1lan/Lc04MxH1SealedPrivateLanPhone.kt")
        assertContains(facade, "fun completeSourceKeyHandoffAfterTvEof()")
        assertContains(facade, "fun completeSyntheticExchange()")
        val gradle = read("local-control/mxh1-lan/build.gradle.kts")
        assertContains(gradle, """:local-control:source-aead""")
        for (source in listOf(tv, facade, gradle)) {
            for (blocked in listOf(
                "SourceOnboarding", "SourcePreparationRequest", "SourcePreparationHandle",
                "HttpClient", "SharedPreferences", "getPassword(", "getUsername(",
            )) assertFalse(source.contains(blocked), blocked)
        }
    }

    @Test fun `installed apps remain inert and hostile real socket tests are required`() {
        val androidPhone = read(
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt")
        val androidTv = read(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt")
        for (app in listOf(androidPhone, androidTv)) {
            assertFalse(app.contains("completeSourceKeyHandoffAfterTvEof"))
            assertFalse(app.contains("takeSourceKeyPortAfterRealF6OutputClose"))
            assertFalse(app.contains("startOnApprovedPrivateIpv4WithSourceKeyHandoff"))
        }
        val tests = read("local-control/mxh1-lan/src/test/kotlin/app/muxtv/" +
            "localcontrol/mxh1lan/Lc04MxH1SealedPrivateLanTvTest.kt")
        for (testName in listOf(
            "nativeSixthAndActualTcpEofRetainOriginalSourceKeysExactlyOnce",
            "diagnosticSixFrameModeNeverExportsAppKey",
            "routeLossAfterRealEofRevokesBothAppDataRecordDirections",
            "originalTvQrExpiryRejectsAuthenticatedSourceFrameAfterKernelEof",
            "ownerStopAfterRealEofClosesClaimedSourcePort",
        )) assertContains(tests, testName)
        assertContains(read(".github/workflows/lc04-mxh1-android-private-lan-ci.yml"),
            "Lc04NativeSourceKeyHandoffContractTest")
    }
}

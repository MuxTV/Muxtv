package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keep the new MXR1 protocol a synthetic-only cryptographic experiment.
 * This is a guard, not a replacement for hostile real-crypto/JVM testing.
 */
class Lc04MxH1SyntheticSealedRecordsContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `mxr1 public API has no arbitrary plaintext or source authority`() {
        val src = root.resolve("local-control/protected-channel/src/main/kotlin/" +
            "app/muxtv/localcontrol/protectedchannel/Lc04MxH1SyntheticSealedRecords.kt")
            .readText()
        for (required in listOf(
            "enum class Lc04SyntheticRecordRole",
            "fun sealSyntheticProbe(): ByteArray?",
            "fun acceptSyntheticProbe(wire: ByteArray): Boolean",
            "keys.claimSyntheticRecordLabOwner()",
            "keys.sealSyntheticLabRecord(",
            "keys.openSyntheticLabRecord(",
            "MAX_RECORDS = 4",
            "TTL_MILLIS = 120_000L",
            "MessageDigest.isEqual(binding, peerBinding)",
            "sequence != received + 1",
            "nonceFor(sequence)",
            "keys.close()",
        )) assertContains(src, required)
        for (blocked in listOf(
            "SourceOnboarding", "SourcePreparationHandle", "SourcePreparationRequest",
            "Xtream", "HttpClient", "SharedPreferences", "ServerSocket",
            "InetSocketAddress", "android.content.", "fun encrypt(",
            "fun send(", "fun seal(plain",
        )) assertFalse(src.contains(blocked), blocked)
    }

    @Test fun `single use key ownership and hosted CI are mandatory`() {
        val keys = root.resolve("local-control/protected-channel/src/main/kotlin/" +
            "app/muxtv/localcontrol/protectedchannel/Lc04SyntheticHandoffKeySchedule.kt")
            .readText()
        for (required in listOf(
            "syntheticRecordLabClaimed",
            "claimSyntheticRecordLabOwner()",
            "Cipher.getInstance(\"AES/GCM/NoPadding\")",
            "GCMParameterSpec(128, nonce)",
            "syntheticRecordLabClaimed = true",
        )) assertContains(keys, required)
        val tests = root.resolve("local-control/protected-channel/src/test/kotlin/" +
            "app/muxtv/localcontrol/protectedchannel/Lc04MxH1SyntheticSealedRecordsTest.kt")
            .readText()
        for (required in listOf(
            "twoRealHkdfDerivedOwnersExchangeOnlyFourSyntheticProbesPerDirection",
            "replayDeniesEntireReceivingKeyOwner",
            "outOfOrderDeniesWithoutGapOrResynchronization",
            "wrongRoleReflectionIsNotAnAuthenticatedPeer",
            "crossedTranscriptCannotDecryptEvenAnUntouchedRecord",
            "headerNonceVersionCounterBindingAndTagTamperingAreFailClosed",
            "truncationAndOversizeAreTerminal",
            "ttlAndMonotonicClockRollbackCloseTheSession",
            "derivedKeyOwnerCannotReopenAndReuseTheSameNonce",
        )) assertContains(tests, required)
        val workflow = root.resolve(
            ".github/workflows/lc04-mxh1-android-private-lan-ci.yml",
        ).readText()
        assertContains(workflow, "local-control/protected-channel/**")
        assertContains(workflow, ":local-control:protected-channel:test")
        assertContains(workflow, "Lc04MxH1SyntheticSealedRecordsContractTest")
        assertFalse(workflow.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxr1-synthetic-sealed-records-20261109.md",
        ).isFile)
    }
}

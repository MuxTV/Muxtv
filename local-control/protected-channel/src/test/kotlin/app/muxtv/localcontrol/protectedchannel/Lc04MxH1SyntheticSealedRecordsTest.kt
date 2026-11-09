package app.muxtv.localcontrol.protectedchannel

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/** Only synthetic HKDF keys and fixed-domain probes; no source, socket or secrets. */
class Lc04MxH1SyntheticSealedRecordsTest {
    private class PairFixture(sessionId: Byte = 9) : AutoCloseable {
        val clock = AtomicLong(10_000L)
        private val seed = ByteArray(32) { (it + 1).toByte() }
        private val transcript = Lc04SyntheticHandoffTranscript(
            ByteArray(16) { 1 }, ByteArray(16) { 2 },
            ByteArray(32) { 3 }, ByteArray(32) { 4 },
            ByteArray(32) { sessionId }, ByteArray(32) { 5 },
        )
        val phone = Lc04MxH1SyntheticSealedRecords.fromDerivedLabKeys(
            Lc04SyntheticHandoffKeySchedule.derive(seed, transcript),
            Lc04SyntheticRecordRole.PHONE, clock::get,
        )
        val tv = Lc04MxH1SyntheticSealedRecords.fromDerivedLabKeys(
            Lc04SyntheticHandoffKeySchedule.derive(seed, transcript),
            Lc04SyntheticRecordRole.TV, clock::get,
        )
        init { seed.fill(0) }
        override fun close() { phone.close(); tv.close() }
    }

    private fun fixture(action: (PairFixture) -> Unit) {
        val f = PairFixture()
        try { action(f) } finally { f.close() }
    }

    @Test fun twoRealHkdfDerivedOwnersExchangeOnlyFourSyntheticProbesPerDirection() =
        fixture { f ->
            repeat(4) {
                assertThat(f.tv.acceptSyntheticProbe(checkNotNull(f.phone.sealSyntheticProbe())))
                    .isTrue()
                assertThat(f.phone.acceptSyntheticProbe(checkNotNull(f.tv.sealSyntheticProbe())))
                    .isTrue()
            }
            assertThat(f.phone.sealSyntheticProbe()).isNull()
            assertThat(f.tv.sealSyntheticProbe()).isNull()
            assertThat(f.phone.toString()).contains("[REDACTED]")
        }

    @Test fun replayDeniesEntireReceivingKeyOwner() = fixture { f ->
        val wire = checkNotNull(f.phone.sealSyntheticProbe())
        assertThat(f.tv.acceptSyntheticProbe(wire)).isTrue()
        assertThat(f.tv.acceptSyntheticProbe(wire)).isFalse()
        assertThat(f.tv.acceptSyntheticProbe(checkNotNull(f.phone.sealSyntheticProbe())))
            .isFalse()
    }

    @Test fun outOfOrderDeniesWithoutGapOrResynchronization() = fixture { f ->
        checkNotNull(f.phone.sealSyntheticProbe())
        val second = checkNotNull(f.phone.sealSyntheticProbe())
        assertThat(f.tv.acceptSyntheticProbe(second)).isFalse()
        assertThat(f.tv.acceptSyntheticProbe(second)).isFalse()
    }

    @Test fun wrongRoleReflectionIsNotAnAuthenticatedPeer() = fixture { f ->
        val outgoing = checkNotNull(f.phone.sealSyntheticProbe())
        assertThat(f.phone.acceptSyntheticProbe(outgoing)).isFalse()
        assertThat(f.phone.sealSyntheticProbe()).isNull()
        assertThat(f.tv.acceptSyntheticProbe(outgoing)).isTrue()
    }

    @Test fun crossedTranscriptCannotDecryptEvenAnUntouchedRecord() {
        val a = PairFixture(8)
        val b = PairFixture(9)
        try {
            val frame = checkNotNull(a.phone.sealSyntheticProbe())
            assertThat(b.tv.acceptSyntheticProbe(frame)).isFalse()
            assertThat(a.tv.acceptSyntheticProbe(frame)).isTrue()
        } finally { a.close(); b.close() }
    }

    @Test fun headerNonceVersionCounterBindingAndTagTamperingAreFailClosed() {
        for (byteIndex in listOf(0, 4, 6, 38, 42, 44, 103)) fixture { f ->
            val original = checkNotNull(f.phone.sealSyntheticProbe())
            val tampered = original.clone().also {
                it[byteIndex] = (it[byteIndex].toInt() xor 1).toByte()
            }
            assertThat(f.tv.acceptSyntheticProbe(tampered)).isFalse()
            assertThat(f.tv.acceptSyntheticProbe(original)).isFalse()
        }
    }

    @Test fun truncationAndOversizeAreTerminal() {
        for (length in listOf(0, 1, 55, 56, 103, 105, 4096)) fixture { f ->
            val wire = checkNotNull(f.phone.sealSyntheticProbe())
            assertThat(f.tv.acceptSyntheticProbe(wire.copyOf(length))).isFalse()
            assertThat(f.tv.acceptSyntheticProbe(wire)).isFalse()
        }
    }

    @Test fun nonceIsCounterUniqueAndDirectionKeysAreDifferent() = fixture { f ->
        val first = checkNotNull(f.phone.sealSyntheticProbe())
        val second = checkNotNull(f.phone.sealSyntheticProbe())
        val tvFirst = checkNotNull(f.tv.sealSyntheticProbe())
        assertThat(first.copyOfRange(44, 56).contentEquals(second.copyOfRange(44, 56)))
            .isFalse()
        // Identical first counter/nonce in the two directions cannot be reflected,
        // because each direction uses a different HKDF-derived AES traffic key.
        assertThat(f.phone.acceptSyntheticProbe(first)).isFalse()
        assertThat(f.tv.acceptSyntheticProbe(tvFirst)).isFalse()
    }

    @Test fun ttlAndMonotonicClockRollbackCloseTheSession() {
        fixture { f ->
            f.clock.addAndGet(120_000L)
            assertThat(f.phone.sealSyntheticProbe()).isNull()
            assertThat(f.tv.acceptSyntheticProbe(ByteArray(104))).isFalse()
        }
        fixture { f ->
            assertThat(f.phone.sealSyntheticProbe()).isNotNull()
            f.clock.decrementAndGet()
            assertThat(f.phone.sealSyntheticProbe()).isNull()
        }
    }

    @Test fun derivedKeyOwnerCannotReopenAndReuseTheSameNonce() {
        val now = AtomicLong(50L)
        val seed = ByteArray(32) { (it + 1).toByte() }
        val transcript = Lc04SyntheticHandoffTranscript(
            ByteArray(16) { 1 }, ByteArray(16) { 2 },
            ByteArray(32) { 3 }, ByteArray(32) { 4 },
            ByteArray(32) { 5 }, ByteArray(32) { 6 },
        )
        val keys = Lc04SyntheticHandoffKeySchedule.derive(seed, transcript)
        seed.fill(0)
        val owner = Lc04MxH1SyntheticSealedRecords.fromDerivedLabKeys(
            keys, Lc04SyntheticRecordRole.PHONE, now::get,
        )
        try {
            assertThat(runCatching {
                Lc04MxH1SyntheticSealedRecords.fromDerivedLabKeys(
                    keys, Lc04SyntheticRecordRole.PHONE, now::get,
                )
            }.isFailure).isTrue()
        } finally { owner.close() }
        assertThat(runCatching {
            Lc04MxH1SyntheticSealedRecords.fromDerivedLabKeys(
                keys, Lc04SyntheticRecordRole.TV, now::get,
            )
        }.isFailure).isTrue()
    }

    @Test fun explicitCloseRevokesBothDirections() = fixture { f ->
        f.phone.close()
        assertThat(f.phone.sealSyntheticProbe()).isNull()
        assertThat(f.phone.acceptSyntheticProbe(ByteArray(104))).isFalse()
    }
}

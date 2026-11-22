package app.muxtv.localcontrol.protectedchannel

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * A bidirectional synthetic-only security model, NOT provider data or a
 * completed mutually authenticated phone-to-TV production transport.
 */
class Lc04SyntheticDuplexTest {
    private val monotonic = AtomicLong(10_000)
    private fun pair() = Lc04SyntheticDuplexEndpoint.newSyntheticPairForLab(monotonic::get)

    private fun withPair(test: (
        phone: Lc04SyntheticDuplexEndpoint,
        tv: Lc04SyntheticDuplexEndpoint,
    ) -> Unit) {
        val (phone, tv) = pair()
        try { test(phone, tv) } finally { phone.close(); tv.close() }
    }

    @Test fun encryptedSyntheticProbeAuthenticatesBothDirections() = withPair { phone, tv ->
        val phoneWire = checkNotNull(phone.sendSyntheticProbe())
        assertThat(phoneWire.copyOfRange(0, 4)).isEqualTo(
            byteArrayOf(0x4d, 0x58, 0x45, 0x31),
        )
        assertThat(phoneWire.size).isEqualTo(113)
        assertThat(tv.receiveSyntheticProbe(phoneWire)).isTrue()
        val tvWire = checkNotNull(tv.sendSyntheticProbe())
        assertThat(phone.receiveSyntheticProbe(tvWire)).isTrue()
        assertThat(phoneWire.contentEquals(tvWire)).isFalse()
        assertThat(phone.toString()).doesNotContain("AES")
    }

    @Test fun repeatedPlaintextProbesHaveFreshTinkNoncesAndStrictCounters() =
        withPair { phone, tv ->
            val first = checkNotNull(phone.sendSyntheticProbe())
            val second = checkNotNull(phone.sendSyntheticProbe())
            assertThat(first.contentEquals(second)).isFalse()
            assertThat(tv.receiveSyntheticProbe(first)).isTrue()
            assertThat(tv.receiveSyntheticProbe(second)).isTrue()
        }

    @Test fun duplicatedPacketClosesReceiverAndCannotBeReplayed() =
        withPair { phone, tv ->
            val packet = checkNotNull(phone.sendSyntheticProbe())
            assertThat(tv.receiveSyntheticProbe(packet)).isTrue()
            assertThat(tv.receiveSyntheticProbe(packet)).isFalse()
            assertThat(tv.receiveSyntheticProbe(checkNotNull(phone.sendSyntheticProbe())))
                .isFalse()
        }

    @Test fun reflectedPacketCannotBeAcceptedBySender() = withPair { phone, tv ->
        val outbound = checkNotNull(phone.sendSyntheticProbe())
        assertThat(phone.receiveSyntheticProbe(outbound)).isFalse()
        assertThat(tv.receiveSyntheticProbe(outbound)).isTrue()
        assertThat(phone.sendSyntheticProbe()).isNull()
    }

    @Test fun outOfOrderPacketTerminatesSessionEvenIfFirstArrivesLater() =
        withPair { phone, tv ->
            val first = checkNotNull(phone.sendSyntheticProbe())
            val second = checkNotNull(phone.sendSyntheticProbe())
            assertThat(tv.receiveSyntheticProbe(second)).isFalse()
            assertThat(tv.receiveSyntheticProbe(first)).isFalse()
        }

    @Test fun wrongAuthenticatedBindingCannotBeAccepted() = withPair { phone, tv ->
        val (foreignPhone, foreignTv) = pair()
        try {
            val packet = checkNotNull(foreignPhone.sendSyntheticProbe())
            assertThat(tv.receiveSyntheticProbe(packet)).isFalse()
            assertThat(foreignTv.receiveSyntheticProbe(packet)).isTrue()
        } finally { foreignPhone.close(); foreignTv.close() }
    }

    @Test fun changingDirectionSessionCounterOrLengthInvalidatesTagOrHeader() {
        for (index in listOf(5, 6, 37, 38, 45, 46, 47, 48, 53, 86, 107, 112)) {
            withPair { phone, tv ->
                val packet = checkNotNull(phone.sendSyntheticProbe())
                packet[index] = (packet[index].toInt() xor 1).toByte()
                assertThat(tv.receiveSyntheticProbe(packet)).isFalse()
                assertThat(tv.receiveSyntheticProbe(checkNotNull(phone.sendSyntheticProbe())))
                    .isFalse()
            }
        }
    }

    @Test fun malformedTruncatedAndExtendedFramesFailClosed() {
        withPair { phone, tv ->
            val packet = checkNotNull(phone.sendSyntheticProbe())
            assertThat(tv.receiveSyntheticProbe(packet.copyOf(packet.size - 1))).isFalse()
            assertThat(tv.receiveSyntheticProbe(packet)).isFalse()
        }
        withPair { phone, tv ->
            val packet = checkNotNull(phone.sendSyntheticProbe())
            assertThat(tv.receiveSyntheticProbe(packet + byteArrayOf(0))).isFalse()
        }
    }

    @Test fun expiryAndMonotonicClockRollbackRevokeBothDirections() {
        withPair { phone, tv ->
            monotonic.addAndGet(120_000)
            assertThat(phone.sendSyntheticProbe()).isNull()
            assertThat(tv.receiveSyntheticProbe(ByteArray(113))).isFalse()
        }
        monotonic.set(10_000)
        withPair { phone, tv ->
            monotonic.decrementAndGet()
            assertThat(tv.sendSyntheticProbe()).isNull()
            assertThat(phone.sendSyntheticProbe()).isNull()
        }
        monotonic.set(10_000)
    }

    @Test fun counterCapacityIsBoundedTo64WithoutWrapOrReuse() =
        withPair { phone, tv ->
            repeat(64) {
                assertThat(tv.receiveSyntheticProbe(checkNotNull(phone.sendSyntheticProbe())))
                    .isTrue()
            }
            assertThat(phone.sendSyntheticProbe()).isNull()
            assertThat(tv.receiveSyntheticProbe(ByteArray(113))).isFalse()
        }

    @Test fun cancellationIsPermanentAndDoesNotReleaseKeysOrProbeBytes() =
        withPair { phone, tv ->
            phone.close()
            phone.close()
            assertThat(phone.sendSyntheticProbe()).isNull()
            assertThat(phone.receiveSyntheticProbe(checkNotNull(tv.sendSyntheticProbe())))
                .isFalse()
            assertThat(phone.toString()).contains("[REDACTED]")
        }
}

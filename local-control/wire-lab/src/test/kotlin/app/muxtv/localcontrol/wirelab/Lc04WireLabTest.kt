package app.muxtv.localcontrol.wirelab

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

class Lc04WireLabTest {
    private val invitation = ByteArray(16) { it.toByte() }
    private val pairing = ByteArray(16) { (it + 16).toByte() }
    private val tvFp = ByteArray(32) { (it + 32).toByte() }
    private val phoneFp = ByteArray(32) { (it + 64).toByte() }

    private fun frame(kind: Lc04WireKind, payload: ByteArray) =
        Lc04WireFrame(kind, invitation, pairing, tvFp, phoneFp, payload)

    @Test fun everyKindAndBoundaryLengthIsCanonicalOnRoundTrip() {
        for (kind in Lc04WireKind.entries) {
            for (size in setOf(kind.minPayload, kind.maxPayload)) {
                val input = ByteArray(size) { (it * 31).toByte() }
                val wire = Lc04WireLab.encode(frame(kind, input))
                assertThat(wire.size).isEqualTo(104 + size)
                val decoded = Lc04WireLab.decode(wire)!!
                assertThat(decoded.kind).isEqualTo(kind)
                assertThat(decoded.payload()).isEqualTo(input)
                assertThat(decoded.tvInvitationId()).isEqualTo(invitation)
                assertThat(decoded.tvPairingId()).isEqualTo(pairing)
                assertThat(decoded.tvHpkeFingerprint()).isEqualTo(tvFp)
                assertThat(decoded.phoneKeyFingerprint()).isEqualTo(phoneFp)
                assertThat(Lc04WireLab.encode(decoded)).isEqualTo(wire)
            }
        }
    }

    @Test fun everyTruncatedPrefixAndAppendedByteIsRejected() {
        val wire = Lc04WireLab.encode(frame(Lc04WireKind.SIGNED_CHANNEL_PROBE, ByteArray(200)))
        for (n in 0 until wire.size) {
            assertThat(Lc04WireLab.decode(wire.copyOf(n))).isNull()
        }
        assertThat(Lc04WireLab.decode(wire + 0x00.toByte())).isNull()
        assertThat(Lc04WireLab.decode(wire + ByteArray(4097))).isNull()
    }

    @Test fun wrongMagicVersionOperationOrNonCanonicalLengthIsRejected() {
        val clean = Lc04WireLab.encode(frame(Lc04WireKind.TV_CHALLENGE, ByteArray(32)))
        for (index in 0..5) {
            val bad = clean.clone()
            bad[index] = (bad[index].toInt() xor 1).toByte()
            assertThat(Lc04WireLab.decode(bad)).isNull()
        }
        for (kind in listOf(0, 6, 127, 255)) {
            val bad = clean.clone()
            bad[5] = kind.toByte()
            assertThat(Lc04WireLab.decode(bad)).isNull()
        }
        for (size in listOf(0, 1, 31, 33, 255, 256, 4096, 65535)) {
            val bad = clean.clone()
            bad[102] = (size ushr 8).toByte()
            bad[103] = size.toByte()
            assertThat(Lc04WireLab.decode(bad)).isNull()
        }
    }

    @Test fun immutableCopyOwnershipAndNoSecretsInDebugString() {
        val input = ByteArray(32) { 0x53 }
        val offer = frame(Lc04WireKind.TV_CHALLENGE, input)
        input.fill(0)
        assertThat(offer.payload()).isNotEqualTo(input)
        val extracted = offer.payload().also { it.fill(0) }
        assertThat(offer.payload()).isNotEqualTo(extracted)
        invitation.fill(0)
        // Do not compare mutable fixture fields here; they are copied at frame creation.
        assertThat(offer.tvInvitationId()[1]).isEqualTo(1.toByte())
        assertThat(offer.toString()).contains("[REDACTED]")
        assertThat(offer.toString()).doesNotContain("SSSS")
    }

    @Test fun rejectsInvalidPayloadBoundsAndContextWidths() {
        for (kind in Lc04WireKind.entries) {
            for (size in listOf(kind.minPayload - 1, kind.maxPayload + 1)) {
                var failed = false
                try { frame(kind, ByteArray(size)) } catch (_: IllegalArgumentException) {
                    failed = true
                }
                assertThat(failed).isTrue()
            }
        }
        var failed = false
        try {
            Lc04WireFrame(Lc04WireKind.TV_CHALLENGE, ByteArray(15), pairing, tvFp, phoneFp, ByteArray(32))
        } catch (_: IllegalArgumentException) { failed = true }
        assertThat(failed).isTrue()
    }

    @Test fun hostileRandomBytesNeverThrowAndAcceptedFramesRemainCanonical() {
        val rng = Random(17042026L)
        repeat(2000) {
            val length = rng.nextInt(4400)
            val bytes = ByteArray(length).also(rng::nextBytes)
            val decoded = Lc04WireLab.decode(bytes)
            if (decoded != null) assertThat(Lc04WireLab.encode(decoded)).isEqualTo(bytes)
        }
    }

    @Test fun codecDoesNotTreatDecodedContextOrPayloadAsAuthorization() {
        val original = Lc04WireLab.encode(frame(Lc04WireKind.PHONE_PROOF, ByteArray(64)))
        val attacker = original.clone()
        attacker[38] = (attacker[38].toInt() xor 4).toByte()
        assertThat(Lc04WireLab.decode(attacker)).isNotNull()
        // Intentional: this layer does NOT authenticate an externally supplied fingerprint.
        // The TV signing/enrollment layer MUST verify every contextual field.
        assertThat(Lc04WireLab.decode(attacker)!!.tvHpkeFingerprint()).isNotEqualTo(tvFp)
    }
}

package app.muxtv.localcontrol.cryptolab

import app.muxtv.localcontrol.wirelab.Lc04ProbeBody
import app.muxtv.localcontrol.wirelab.Lc04ReceiptBody
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * GitHub-hosted JVM ONLY. An untrusted on-path relay operates solely on serialized
 * bytes. Both simulated endpoints use the EXISTING JDK17 Ed25519 and Tink HPKE lab
 * plus an actual AES-256-GCM authenticated TV reply. No Android permission,
 * private network listener, production protocol or IPTV credential transfer.
 *
 * Critical limitation: the 2026-10-18 typed wire transcript in #415 and
 * LabSignedHpkeEnvelope/LabAuthenticatedTvReply are DIFFERENT experimental
 * signature/AAD formats. These tests exercise the accepted #413 lab signature
 * and AEAD checks over #414/#415 canonical wire STRUCTURE, not the final
 * frozen production on-wire cryptographic transcript.
 */
class LabHostileLanWireTest {
    private val binding = LabBinding("1".repeat(32), "2".repeat(32))
    private val tvFingerprint = "3".repeat(64)

    private class PairFixture(
        val binding: LabBinding,
        val tvFingerprint: String,
        val phoneFingerprint: String,
        val phoneIdentity: LabPhoneIdentity,
        val tvReceiver: LabReceiver,
        val tvVerifier: LabSignedHpkeTv,
        val phone: LabReplyPhone,
        val tv: LabAuthenticatedReplyTv,
    ) {
        fun request(): Pair<LabPendingTvReply, ByteArray> {
            val pending = phone.initiate()
            val cipher = pending.signedRequest().cipher()
            val body = Lc04ProbeBody(cipher.counter, cipher.bytes, pending.signedRequest().signedBytes())
            val frame = Lc04WireFrame(
                Lc04WireKind.SIGNED_CHANNEL_PROBE,
                hexToBytes(binding.tvInvitationId),
                hexToBytes(binding.approvedPairingId),
                hexToBytes(tvFingerprint),
                hexToBytes(phoneFingerprint),
                Lc04TypedWire.encodeProbe(body),
            )
            return pending to Lc04WireLab.encode(frame)
        }

        /** TV endpoint: check pinned public context BEFORE converting wire to signed HPKE. */
        fun receive(frameBytes: ByteArray): ByteArray? {
            val frame = Lc04WireLab.decode(frameBytes) ?: return null
            if (!contextMatches(frame) || frame.kind != Lc04WireKind.SIGNED_CHANNEL_PROBE) return null
            val typed = Lc04TypedWire.decodeProbeFrame(frame) ?: return null
            val envelope = LabSignedEnvelope(
                LabCiphertext(LabOperation.CHANNEL_PROBE, typed.counter, typed.ciphertext()),
                typed.signature(),
            )
            val signedResult = tv.replyTo(envelope)
            val reply = (signedResult as? LabTvReplyResult.Sent)?.reply ?: return null
            val receipt = Lc04ReceiptBody(
                reply.counter, reply.requestId(), reply.iv(), reply.ciphertext(),
            )
            return Lc04WireLab.encode(Lc04WireFrame(
                Lc04WireKind.SEALED_TV_RECEIPT,
                hexToBytes(binding.tvInvitationId),
                hexToBytes(binding.approvedPairingId),
                hexToBytes(tvFingerprint),
                hexToBytes(phoneFingerprint),
                Lc04TypedWire.encodeReceipt(receipt),
            ))
        }

        /** Phone endpoint: never trust the relay-supplied IDs or request reference. */
        fun accept(replyBytes: ByteArray, pending: LabPendingTvReply): Boolean {
            val frame = Lc04WireLab.decode(replyBytes) ?: return false
            if (frame.kind != Lc04WireKind.SEALED_TV_RECEIPT || !contextMatches(frame)) return false
            val receipt = Lc04TypedWire.decodeReceiptFrame(frame) ?: return false
            return pending.verify(LabSealedTvReply(
                receipt.counter, receipt.requestDigest(), receipt.iv(), receipt.ciphertext(),
            ))
        }

        private fun contextMatches(frame: Lc04WireFrame): Boolean =
            frame.tvInvitationId().contentEquals(hexToBytes(binding.tvInvitationId)) &&
            frame.tvPairingId().contentEquals(hexToBytes(binding.approvedPairingId)) &&
            frame.tvHpkeFingerprint().contentEquals(hexToBytes(tvFingerprint)) &&
            frame.phoneKeyFingerprint().contentEquals(hexToBytes(phoneFingerprint))
    }

    private fun paired(): PairFixture {
        val receiver = ExperimentalHpkeLab.newReceiver(binding)
        val phone = LabPhoneIdentity.generate()
        val enrollment = LabTvPhoneEnrollment(binding, tvFingerprint, elapsedMillis = { 1_000L })
        val challenge = enrollment.beginEnrollment(phone.publicKeyBytes())
        // Simulates explicit TV-owner consent; an actual LAN packet cannot call this API.
        assertThat(enrollment.approveOnTv(phone.fingerprint)).isTrue()
        val verified = enrollment.verifyProof(phone.sign(binding, tvFingerprint, challenge))
            as LabEnrollmentOutcome.ProofVerified
        val signer = LabSignedHpkePhone(binding, tvFingerprint, phone, receiver.publicKeyset)
        val verifier = LabSignedHpkeTv(binding, tvFingerprint, receiver, verified)
        return PairFixture(
            binding, tvFingerprint, phone.fingerprint, phone, receiver, verifier,
            LabReplyPhone(binding, tvFingerprint, phone.fingerprint, signer),
            LabAuthenticatedReplyTv(binding, tvFingerprint, phone.fingerprint, verifier),
        )
    }

    /**
     * A fully attacker-controlled byte relay. Each edge is a separate copy.
     * Null represents a lost packet; the relay has no signing, HPKE or reply keys.
     */
    private fun relay(bytes: ByteArray, transform: (ByteArray) -> ByteArray? = { it }): ByteArray? =
        transform(bytes.clone())?.clone()

    @Test fun fullByteRoundTripAuthenticatesSignedHpkeAndAeadAndConsumesResponse() {
        val f = paired()
        val (pending, outgoing) = f.request()
        val received = f.receive(relay(outgoing)!!)!!
        assertThat(f.accept(relay(received)!!, pending)).isTrue()
        assertThat(f.accept(received, pending)).isFalse()
        assertThat(f.receive(outgoing)).isNull() // TV receiver rejected duplicate counter
        pending.close()
    }

    @Test fun hostileLanCannotAlterPhoneSignatureCiphertextCounterOrPinnedQrContext() {
        val f = paired()
        val (pending, original) = f.request()
        val targetIndices = listOf(
            6, 22, 38, 70, // TV invitation/pairing/TV public key/phone key fingerprints
            104 + 1, // signed request counter low byte
            104 + 4 + 15, // HPKE ciphertext
            original.lastIndex, // Ed25519 signature
        )
        for (index in targetIndices) {
            val corrupted = relay(original)!!.also {
                it[index] = (it[index].toInt() xor 1).toByte()
            }
            assertThat(f.receive(corrupted)).isNull()
        }
        val authenticatedReply = f.receive(original)!!
        assertThat(f.accept(authenticatedReply, pending)).isTrue()
    }

    @Test fun forgedOrRewrittenTvReplyNeverPassesGcmAndGenuineReceiptStillWorks() {
        val f = paired()
        val (pending, request) = f.request()
        val reply = f.receive(request)!!
        val offsets = listOf(
            6, 22, 38, 70, // decoded frame's original pinned identities
            104 + 1, // counter
            104 + 2, // signed request digest
            104 + 34, // AES-GCM IV
            reply.lastIndex, // ciphertext authentication tag
        )
        for (i in offsets) {
            val attacker = relay(reply)!!.also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThat(f.accept(attacker, pending)).isFalse()
            // Invalid structural context is rejected before invoking LabPendingTvReply
            // and therefore does not spend a crypto verification attempt.
            // GCM-tag modifications can exhaust the lab's 3-attempt budget.
            if (i == reply.lastIndex) break
        }
        assertThat(f.accept(reply, pending)).isTrue()
    }

    @Test fun attackerWithTvPublicHpkeKeyCannotImpersonateApprovedPhone() {
        val f = paired()
        val attackerIdentity = LabPhoneIdentity.generate()
        val attacker = LabSignedHpkePhone(binding, tvFingerprint, attackerIdentity,
            f.tvReceiver.publicKeyset)
        val encrypted = attacker.send(LabOperation.CHANNEL_PROBE, ByteArray(48) { 6 })
        val cipher = encrypted.cipher()
        val forged = Lc04WireLab.encode(Lc04WireFrame(
            Lc04WireKind.SIGNED_CHANNEL_PROBE,
            hexToBytes(binding.tvInvitationId), hexToBytes(binding.approvedPairingId),
            hexToBytes(tvFingerprint), hexToBytes(f.phoneFingerprint),
            Lc04TypedWire.encodeProbe(
                Lc04ProbeBody(cipher.counter, cipher.bytes, encrypted.signedBytes()),
            ),
        ))
        assertThat(f.receive(forged)).isNull()
        // Bad signer did not consume the honest sender's expected counter.
        val (pending, genuine) = f.request()
        assertThat(f.accept(f.receive(genuine)!!, pending)).isTrue()
    }

    @Test fun untrustedLanCanDropDelayOrReorderButNeverCreateAuthenticatedSuccess() {
        val f = paired()
        val (oldPending, oldBytes) = f.request()
        val (newPending, newBytes) = f.request()
        assertThat(relay(oldBytes) { null }).isNull()
        // TV accepts later monotonic counter first; earlier one is stale thereafter.
        val latestReply = f.receive(relay(newBytes)!!)!!
        assertThat(f.receive(relay(oldBytes)!!)).isNull()
        assertThat(f.accept(latestReply, oldPending)).isFalse()
        assertThat(f.accept(latestReply, newPending)).isTrue()
        oldPending.close()
        assertThat(f.accept(latestReply, oldPending)).isFalse()
    }

    @Test fun revokedPhoneApprovalPreventsAnyTvReplyThroughWire() {
        val f = paired()
        val (pending, request) = f.request()
        f.tvVerifier.revoke()
        assertThat(f.receive(request)).isNull()
        pending.close()
    }

    @Test fun truncatedAndOverlongPacketsRejectedAtBothEndpoints() {
        val f = paired()
        val (pending, request) = f.request()
        for (n in listOf(0, 1, 3, 5, 103, 104, 119, request.size - 1)) {
            assertThat(f.receive(request.copyOf(n))).isNull()
        }
        assertThat(f.receive(request + 0.toByte())).isNull()
        val reply = f.receive(request)!!
        assertThat(f.accept(reply.copyOf(reply.size - 1), pending)).isFalse()
        assertThat(f.accept(reply + 0.toByte(), pending)).isFalse()
        assertThat(f.accept(reply, pending)).isTrue()
    }

    companion object {
        private fun hexToBytes(s: String): ByteArray {
            require(s.length % 2 == 0)
            return ByteArray(s.length / 2) { i ->
                val hi = s[i * 2].digitToInt(16)
                val lo = s[i * 2 + 1].digitToInt(16)
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}

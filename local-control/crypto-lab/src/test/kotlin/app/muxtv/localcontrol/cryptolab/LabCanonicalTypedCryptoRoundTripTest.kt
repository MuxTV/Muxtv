package app.muxtv.localcontrol.cryptolab

import app.muxtv.localcontrol.wirelab.Lc04ProbeBody
import app.muxtv.localcontrol.wirelab.Lc04ReceiptBody
import app.muxtv.localcontrol.wirelab.Lc04TypedTranscript
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

/**
 * Actual JVM Ed25519 + Tink HPKE + AES-GCM over accepted canonical #415 bytes.
 * This is deliberately test-only: no product LAN API, Android provider or source action.
 *
 * Unlike the legacy #413 lab, BOTH phone and TV use Lc04TypedTranscript to
 * build the signed request and reverse AES-GCM AAD. Accepted Android-native
 * interoperation / product trust is NOT established by this JDK-only exercise.
 */
class LabCanonicalTypedCryptoRoundTripTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))
    private val tvFingerprint = "c".repeat(64)
    private val random = SecureRandom()

    private class Fixture(
        val phoneIdentity: LabPhoneIdentity,
        val receiver: LabReceiver,
        val binding: LabBinding,
        val tvFingerprint: String,
        val secret: ByteArray,
        val challenge: ByteArray,
        val signedRequestBytes: ByteArray,
    ) {
        val approvedPublicKey: ByteArray = phoneIdentity.publicKeyBytes()

        fun tvReply(inboundBytes: ByteArray): ByteArray? {
            // Only decoded bytes are received from the hostile byte relay.
            val frame = Lc04WireLab.decode(inboundBytes) ?: return null
            if (frame.kind != Lc04WireKind.SIGNED_CHANNEL_PROBE ||
                !MessageDigest.isEqual(frame.tvInvitationId(), hex(binding.tvInvitationId)) ||
                !MessageDigest.isEqual(frame.tvPairingId(), hex(binding.approvedPairingId)) ||
                !MessageDigest.isEqual(frame.tvHpkeFingerprint(), hex(tvFingerprint)) ||
                !MessageDigest.isEqual(frame.phoneKeyFingerprint(), hex(phoneIdentity.fingerprint))
            ) return null
            val body = Lc04TypedWire.decodeProbeFrame(frame) ?: return null

            // Exact canonical #415 statement — signature check BEFORE HPKE decrypt.
            val signatureOkay = try {
                val verifier = Signature.getInstance("Ed25519")
                val key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(approvedPublicKey))
                verifier.initVerify(key)
                verifier.update(Lc04TypedTranscript.probeSigningStatement(frame, body))
                verifier.verify(body.signature())
            } catch (_: GeneralSecurityException) {
                false
            }
            if (!signatureOkay) return null
            val opened = receiver.decrypt(
                LabCiphertext(LabOperation.CHANNEL_PROBE, body.counter, body.ciphertext()),
            ) as? LabDecryptResult.Accepted ?: return null

            var encodedReceipt: ByteArray? = null
            opened.consumeOnce { payload ->
                if (payload.size != 48) return@consumeOnce
                val replyKey = payload.copyOfRange(0, 32)
                val phoneNonce = payload.copyOfRange(32, 48)
                val iv = ByteArray(12).also(SecureRandom()::nextBytes)
                val plaintext = "MuxTV-TV-RECEIVED-v1|".toByteArray(Charsets.US_ASCII) + phoneNonce
                val digest = Lc04TypedTranscript.originalSignedRequestDigest(frame)
                try {
                    val placeholder = Lc04ReceiptBody(body.counter, digest, iv,
                        ByteArray(plaintext.size + 16))
                    val placeholderFrame = receiptFrame(placeholder)
                    val aad = Lc04TypedTranscript.receiptAad(placeholderFrame, placeholder)
                    val encryptor = Cipher.getInstance("AES/GCM/NoPadding")
                    encryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(replyKey, "AES"),
                        GCMParameterSpec(128, iv))
                    encryptor.updateAAD(aad)
                    val encrypted = encryptor.doFinal(plaintext)
                    encodedReceipt = Lc04WireLab.encode(receiptFrame(
                        Lc04ReceiptBody(body.counter, digest, iv, encrypted),
                    ))
                } finally {
                    replyKey.fill(0)
                    phoneNonce.fill(0)
                    iv.fill(0)
                    digest.fill(0)
                    plaintext.fill(0)
                }
            }
            return encodedReceipt
        }

        fun phoneVerifies(receivedBytes: ByteArray): Boolean {
            val original = Lc04WireLab.decode(signedRequestBytes) ?: return false
            val frame = Lc04WireLab.decode(receivedBytes) ?: return false
            val aad = Lc04TypedTranscript.aadForOutstandingRequest(original, frame)
                ?: return false
            val receipt = Lc04TypedWire.decodeReceiptFrame(frame) ?: return false
            val opened = try {
                val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
                decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"),
                    GCMParameterSpec(128, receipt.iv()))
                decrypt.updateAAD(aad)
                decrypt.doFinal(receipt.ciphertext())
            } catch (_: GeneralSecurityException) {
                return false
            }
            val expected = "MuxTV-TV-RECEIVED-v1|".toByteArray(Charsets.US_ASCII) + challenge
            return try {
                MessageDigest.isEqual(opened, expected)
            } finally {
                opened.fill(0)
                expected.fill(0)
            }
        }

        private fun receiptFrame(payload: Lc04ReceiptBody): Lc04WireFrame =
            Lc04WireFrame(
                Lc04WireKind.SEALED_TV_RECEIPT,
                hex(binding.tvInvitationId), hex(binding.approvedPairingId),
                hex(tvFingerprint), hex(phoneIdentity.fingerprint),
                Lc04TypedWire.encodeReceipt(payload),
            )
    }

    private fun makeFixture(): Fixture {
        val receiver = ExperimentalHpkeLab.newReceiver(binding)
        val identity = LabPhoneIdentity.generate()
        val enrollment = LabTvPhoneEnrollment(binding, tvFingerprint, elapsedMillis = { 1_000 })
        val nonce = enrollment.beginEnrollment(identity.publicKeyBytes())
        assertThat(enrollment.approveOnTv(identity.fingerprint)).isTrue()
        assertThat(enrollment.verifyProof(identity.sign(binding, tvFingerprint, nonce)))
            .isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)

        val secret = ByteArray(32).also(random::nextBytes)
        val challenge = ByteArray(16).also(random::nextBytes)
        val hpke = LabPhoneSender(binding, receiver.publicKeyset)
            .encrypt(LabOperation.CHANNEL_PROBE, secret + challenge)

        // A zero signature of the right size is only a placeholder for computing
        // the signing statement. It is NEVER sent over a transport.
        val placeholder = Lc04ProbeBody(hpke.counter, hpke.bytes, ByteArray(64))
        val draft = Lc04WireFrame(
            Lc04WireKind.SIGNED_CHANNEL_PROBE,
            hex(binding.tvInvitationId), hex(binding.approvedPairingId),
            hex(tvFingerprint), hex(identity.fingerprint),
            Lc04TypedWire.encodeProbe(placeholder),
        )
        val statement = Lc04TypedTranscript.probeSigningStatement(draft, placeholder)
        val signature = identity.signLabEnvelope(statement)
        val signed = Lc04WireFrame(
            Lc04WireKind.SIGNED_CHANNEL_PROBE,
            hex(binding.tvInvitationId), hex(binding.approvedPairingId),
            hex(tvFingerprint), hex(identity.fingerprint),
            Lc04TypedWire.encodeProbe(Lc04ProbeBody(hpke.counter, hpke.bytes, signature)),
        )
        return Fixture(identity, receiver, binding, tvFingerprint,
            secret, challenge, Lc04WireLab.encode(signed))
    }

    @Test fun canonicalSignedWireAndReverseAadAuthenticateOnBothEnds() {
        val f = makeFixture()
        val authenticated = f.tvReply(f.signedRequestBytes.clone())!!
        assertThat(f.phoneVerifies(authenticated.clone())).isTrue()
        // The TV has consumed the monotonic HPKE counter.
        assertThat(f.tvReply(f.signedRequestBytes)).isNull()
    }

    @Test fun hostileRelayCannotForgeRequestSignatureOrNewTypedContext() {
        val offsets = listOf(6, 22, 38, 70, 105, 111, -1)
        for (offset in offsets) {
            val f = makeFixture()
            val poisoned = f.signedRequestBytes.clone()
            val index = if (offset == -1) poisoned.lastIndex else offset
            poisoned[index] = (poisoned[index].toInt() xor 1).toByte()
            assertThat(f.tvReply(poisoned)).isNull()
            val genuine = f.tvReply(f.signedRequestBytes)!!
            assertThat(f.phoneVerifies(genuine)).isTrue()
        }
    }

    @Test fun hostileRelayCannotForgeTypedAadDigestIvOrGcmTag() {
        val f = makeFixture()
        val reply = f.tvReply(f.signedRequestBytes)!!
        for (offset in listOf(6, 22, 38, 70, 105, 106, 138, -1)) {
            val tampered = reply.clone()
            val index = if (offset == -1) tampered.lastIndex else offset
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertThat(f.phoneVerifies(tampered)).isFalse()
        }
        assertThat(f.phoneVerifies(reply)).isTrue()
    }

    @Test fun truncatedAndExtraPacketsRejectWithoutAlternativeEncoding() {
        val f = makeFixture()
        val original = f.signedRequestBytes
        for (length in listOf(0, 1, 3, 5, 103, 104, original.size - 1)) {
            assertThat(f.tvReply(original.copyOf(length))).isNull()
        }
        assertThat(f.tvReply(original + 0.toByte())).isNull()
        val reply = f.tvReply(original)!!
        assertThat(f.phoneVerifies(reply.copyOf(reply.size - 1))).isFalse()
        assertThat(f.phoneVerifies(reply + 0.toByte())).isFalse()
        assertThat(f.phoneVerifies(reply)).isTrue()
    }

    companion object {
        private fun hex(value: String): ByteArray {
            require(value.length % 2 == 0)
            return ByteArray(value.length / 2) { i ->
                val high = value[i * 2].digitToInt(16)
                val low = value[i * 2 + 1].digitToInt(16)
                ((high shl 4) or low).toByte()
            }
        }
    }
}

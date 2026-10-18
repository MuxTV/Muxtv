package app.muxtv.localcontrol.wirelab

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

/**
 * JDK-only research tests; a passing signature proves the byte transcript design,
 * NOT an authorized session or deployed Android LAN channel.
 */
class Lc04TypedTranscriptTest {
    private val invite = ByteArray(16) { (it + 1).toByte() }
    private val pairing = ByteArray(16) { (it + 17).toByte() }
    private val tv = ByteArray(32) { (it + 33).toByte() }
    private val phone = ByteArray(32) { (it + 65).toByte() }

    private fun probe(
        body: Lc04ProbeBody,
        invitation: ByteArray = invite,
        pairingId: ByteArray = pairing,
        tvFingerprint: ByteArray = tv,
        phoneFingerprint: ByteArray = phone,
    ) = Lc04WireFrame(
        Lc04WireKind.SIGNED_CHANNEL_PROBE,
        invitation, pairingId, tvFingerprint, phoneFingerprint,
        Lc04TypedWire.encodeProbe(body),
    )
    private fun receipt(
        body: Lc04ReceiptBody,
        invitation: ByteArray = invite,
        pairingId: ByteArray = pairing,
        tvFingerprint: ByteArray = tv,
        phoneFingerprint: ByteArray = phone,
    ) = Lc04WireFrame(
        Lc04WireKind.SEALED_TV_RECEIPT,
        invitation, pairingId, tvFingerprint, phoneFingerprint,
        Lc04TypedWire.encodeReceipt(body),
    )

    @Test fun canonicalProbeAndReceiptRoundTripAtBoundaries() {
        for (sigSize in listOf(64, 69)) {
            for (cipherSize in listOf(17, 4022)) {
                val value = Lc04ProbeBody(128, ByteArray(cipherSize) { 7 }, ByteArray(sigSize) { 8 })
                val encoded = Lc04WireLab.encode(probe(value))
                assertThat(encoded.size).isAtMost(Lc04WireLab.MAX_FRAME_BYTES)
                val parsedFrame = Lc04WireLab.decode(encoded)!!
                val parsed = Lc04TypedWire.decodeProbeFrame(parsedFrame)!!
                assertThat(parsed.counter).isEqualTo(128)
                assertThat(parsed.ciphertext()).isEqualTo(value.ciphertext())
                assertThat(parsed.signature()).isEqualTo(value.signature())
                assertThat(Lc04WireLab.encode(parsedFrame)).isEqualTo(encoded)
            }
        }
        for (cipherSize in listOf(17, 4048)) {
            val body = Lc04ReceiptBody(128, ByteArray(32) { 9 }, ByteArray(12) { 10 },
                ByteArray(cipherSize) { 11 })
            val encoded = Lc04WireLab.encode(receipt(body))
            val decoded = Lc04TypedWire.decodeReceiptFrame(Lc04WireLab.decode(encoded)!!)!!
            assertThat(decoded.counter).isEqualTo(128)
            assertThat(decoded.requestDigest()).isEqualTo(body.requestDigest())
            assertThat(decoded.iv()).isEqualTo(body.iv())
            assertThat(decoded.ciphertext()).isEqualTo(body.ciphertext())
        }
    }

    @Test fun typedDecodersRejectTruncationNoncanonicalFieldsAndTail() {
        val original = Lc04TypedWire.encodeProbe(
            Lc04ProbeBody(1, ByteArray(42) { 5 }, ByteArray(69) { 6 }))
        for (n in 0 until original.size) assertThat(Lc04TypedWire.decodeProbe(original.copyOf(n))).isNull()
        assertThat(Lc04TypedWire.decodeProbe(original + 0.toByte())).isNull()
        for ((offset, value) in listOf(0 to 0, 0 to 129, 2 to 0, 2 to 17, 2 to 4096)) {
            val changed = original.clone()
            changed[offset] = (value ushr 8).toByte()
            changed[offset + 1] = value.toByte()
            if (offset == 2 && value == 17) {
                assertThat(Lc04TypedWire.decodeProbe(changed)).isNull()
            } else {
                assertThat(Lc04TypedWire.decodeProbe(changed)).isNull()
            }
        }
        for (length in listOf(0, 1, 63, 65, 68, 70, 255)) {
            val changed = original.clone()
            changed[4 + 42] = length.toByte()
            assertThat(Lc04TypedWire.decodeProbe(changed)).isNull()
        }
        val reply = Lc04TypedWire.encodeReceipt(
            Lc04ReceiptBody(7, ByteArray(32), ByteArray(12), ByteArray(40)))
        for (n in 0 until reply.size) assertThat(Lc04TypedWire.decodeReceipt(reply.copyOf(n))).isNull()
        assertThat(Lc04TypedWire.decodeReceipt(reply + 0.toByte())).isNull()
        val wrongCount = reply.clone()
        wrongCount[1] = 0
        wrongCount[0] = 0
        assertThat(Lc04TypedWire.decodeReceipt(wrongCount)).isNull()
        val wrongLen = reply.clone()
        wrongLen[47] = 39
        assertThat(Lc04TypedWire.decodeReceipt(wrongLen)).isNull()
    }

    @Test fun cryptographicPhoneSignatureBindsCounterTvAndPhoneIdentityContext() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encrypted = ByteArray(93) { (it * 17).toByte() }
        val unsigned = Lc04ProbeBody(1, encrypted, ByteArray(64))
        val unsignedFrame = probe(unsigned)
        val statement = Lc04TypedTranscript.probeSigningStatement(unsignedFrame, unsigned)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(statement)
        val signed = signer.sign()
        assertThat(signed.size).isEqualTo(64)
        val body = Lc04ProbeBody(1, encrypted, signed)
        val frame = probe(body)
        val canonical = Lc04TypedTranscript.probeSigningStatement(frame, body)
        assertThat(canonical).isEqualTo(statement)

        fun verified(otherFrame: Lc04WireFrame, otherBody: Lc04ProbeBody): Boolean {
            val verify = Signature.getInstance("Ed25519")
            verify.initVerify(keys.public)
            verify.update(Lc04TypedTranscript.probeSigningStatement(otherFrame, otherBody))
            return verify.verify(otherBody.signature())
        }
        assertThat(verified(frame, body)).isTrue()
        val alternatives = listOf(
            probe(body, invitation = flipped(invite)),
            probe(body, pairingId = flipped(pairing)),
            probe(body, tvFingerprint = flipped(tv)),
            probe(body, phoneFingerprint = flipped(phone)),
            probe(Lc04ProbeBody(2, encrypted, signed)),
            probe(Lc04ProbeBody(1, flipped(encrypted), signed)),
            probe(Lc04ProbeBody(1, encrypted, flipped(signed))),
        )
        for (wrong in alternatives) {
            val parsed = Lc04TypedWire.decodeProbeFrame(wrong)!!
            assertThat(verified(wrong, parsed)).isFalse()
        }
    }

    @Test fun replyAadBindsBothIdentitiesReverseDirectionAndRequestDigest() {
        val secret = ByteArray(32) { 7 }
        val aesKey = SecretKeySpec(secret, "AES")
        val iv = ByteArray(12) { (it + 11).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(ByteArray(69) { 14 })
        val plaintext = "verified-TV-only".toByteArray()
        val preview = Lc04ReceiptBody(3, digest, iv, ByteArray(plaintext.size + 16))
        val associated = Lc04TypedTranscript.receiptAad(receipt(preview), preview)
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        encrypt.updateAAD(associated)
        val cipher = encrypt.doFinal(plaintext)
        val good = Lc04ReceiptBody(3, digest, iv, cipher)
        val frame = receipt(good)
        assertThat(Lc04TypedTranscript.receiptAad(frame, good)).isEqualTo(associated)

        fun opens(maybeFrame: Lc04WireFrame, body: Lc04ReceiptBody): Boolean {
            return try {
                val dec = Cipher.getInstance("AES/GCM/NoPadding")
                dec.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, body.iv()))
                dec.updateAAD(Lc04TypedTranscript.receiptAad(maybeFrame, body))
                dec.doFinal(body.ciphertext()).contentEquals(plaintext)
            } catch (_: java.security.GeneralSecurityException) {
                false
            }
        }
        assertThat(opens(frame, good)).isTrue()
        val wrong = listOf(
            receipt(good, invitation = flipped(invite)),
            receipt(good, pairingId = flipped(pairing)),
            receipt(good, tvFingerprint = flipped(tv)),
            receipt(good, phoneFingerprint = flipped(phone)),
            receipt(Lc04ReceiptBody(4, digest, iv, cipher)),
            receipt(Lc04ReceiptBody(3, flipped(digest), iv, cipher)),
            receipt(Lc04ReceiptBody(3, digest, flipped(iv), cipher)),
            receipt(Lc04ReceiptBody(3, digest, iv, flipped(cipher))),
        )
        wrong.forEach { altered ->
            assertThat(opens(altered, Lc04TypedWire.decodeReceiptFrame(altered)!!)).isFalse()
        }
        assertThat(associated).isNotEqualTo(Lc04TypedTranscript.probeSigningStatement(
            probe(Lc04ProbeBody(3, ByteArray(17), ByteArray(64))),
            Lc04ProbeBody(3, ByteArray(17), ByteArray(64)),
        ))
    }

    @Test fun inputAliasingAndWrongFrameTypeAreRejected() {
        val sig = ByteArray(64) { 8 }
        val cipher = ByteArray(19) { 9 }
        val body = Lc04ProbeBody(3, cipher, sig)
        sig.fill(0)
        cipher.fill(0)
        assertThat(body.signature()).isNotEqualTo(sig)
        assertThat(body.ciphertext()).isNotEqualTo(cipher)
        val copy = body.signature()
        copy.fill(0)
        assertThat(body.signature()).isNotEqualTo(copy)
        assertThat(body.toString()).contains("[REDACTED]")
        val nonce = ByteArray(12) { 12 }
        val replyBody = Lc04ReceiptBody(3, ByteArray(32), nonce, ByteArray(17))
        nonce.fill(0)
        assertThat(replyBody.iv()).isNotEqualTo(nonce)
        assertThat(Lc04TypedWire.decodeProbeFrame(receipt(replyBody))).isNull()
        assertThat(Lc04TypedWire.decodeReceiptFrame(probe(body))).isNull()
    }

    @Test fun randomMalformedPayloadNeverCrashesAndAlwaysRoundTripsIfAccepted() {
        val rng = Random(8003)
        repeat(2500) {
            val sample = ByteArray(rng.nextInt(4210)).also(rng::nextBytes)
            val probe = Lc04TypedWire.decodeProbe(sample)
            if (probe != null) assertThat(Lc04TypedWire.encodeProbe(probe)).isEqualTo(sample)
            val receipt = Lc04TypedWire.decodeReceipt(sample)
            if (receipt != null) assertThat(Lc04TypedWire.encodeReceipt(receipt)).isEqualTo(sample)
        }
    }

    private fun flipped(input: ByteArray): ByteArray = input.clone().also {
        it[0] = (it[0].toInt() xor 1).toByte()
    }
}

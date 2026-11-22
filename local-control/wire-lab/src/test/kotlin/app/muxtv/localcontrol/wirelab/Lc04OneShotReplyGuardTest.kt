package app.muxtv.localcontrol.wirelab

import com.google.common.truth.Truth.assertThat
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

/**
 * JVM/hosted CI only. An in-memory test TV constructs a real AES-GCM response
 * using its test-only knowledge of the key that would have arrived by HPKE.
 * There are no sockets or source-provider messages.
 */
class Lc04OneShotReplyGuardTest {
    private val invitation = ByteArray(16) { (it + 1).toByte() }
    private val pairing = ByteArray(16) { (it + 17).toByte() }
    private val tvFingerprint = ByteArray(32) { (it + 33).toByte() }
    private val phoneFingerprint = ByteArray(32) { (it + 65).toByte() }
    private val replyKey = ByteArray(32) { (it * 9 + 3).toByte() }
    private val challenge = ByteArray(16) { (it * 7 + 5).toByte() }

    private fun probe(counter: Int = 1) = Lc04WireFrame(
        kind = Lc04WireKind.SIGNED_CHANNEL_PROBE,
        tvInvitationId = invitation,
        tvPairingId = pairing,
        tvHpkeFingerprint = tvFingerprint,
        phoneKeyFingerprint = phoneFingerprint,
        payload = Lc04TypedWire.encodeProbe(
            Lc04ProbeBody(counter, ByteArray(96) { (it + 11).toByte() },
                ByteArray(64) { (it + 23).toByte() }),
        ),
    )

    private fun seal(
        original: Lc04WireFrame,
        counter: Int = 1,
        digest: ByteArray = Lc04TypedTranscript.originalSignedRequestDigest(original),
        tv: ByteArray = tvFingerprint,
        phone: ByteArray = phoneFingerprint,
        iv: ByteArray = ByteArray(12) { (it + 43).toByte() },
        secret: ByteArray = replyKey,
        confirmedChallenge: ByteArray = challenge,
    ): Lc04WireFrame {
        val plaintext = "MuxTV-TV-RECEIVED-v1|".toByteArray() + confirmedChallenge
        val bodyForAad = Lc04ReceiptBody(counter, digest, iv, ByteArray(plaintext.size + 16))
        val placeholder = Lc04WireFrame(
            Lc04WireKind.SEALED_TV_RECEIPT, invitation, pairing, tv, phone,
            Lc04TypedWire.encodeReceipt(bodyForAad),
        )
        val associated = Lc04TypedTranscript.receiptAad(placeholder, bodyForAad)
        val cryptor = Cipher.getInstance("AES/GCM/NoPadding")
        cryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"),
            GCMParameterSpec(128, iv))
        cryptor.updateAAD(associated)
        val ciphertext = cryptor.doFinal(plaintext)
        return Lc04WireFrame(
            Lc04WireKind.SEALED_TV_RECEIPT, invitation, pairing, tv, phone,
            Lc04TypedWire.encodeReceipt(Lc04ReceiptBody(counter, digest, iv, ciphertext)),
        )
    }

    private fun guard(
        original: Lc04WireFrame,
        now: () -> Long = { 10_000 },
        ttl: Long = 10_000,
        maxFailures: Int = 3,
        key: ByteArray = replyKey,
        nonce: ByteArray = challenge,
    ) = Lc04OneShotReplyGuard(
        original, key, nonce, 10_000, now, ttlMillis = ttl, maxFailedReplies = maxFailures,
    )

    @Test fun honestResponseAcceptedExactlyOnceAndReplayedReceiptDenied() {
        val original = probe()
        val pending = guard(original)
        val valid = seal(original)
        assertThat(pending.isPending()).isTrue()
        assertThat(pending.verify(valid)).isTrue()
        assertThat(pending.isPending()).isFalse()
        assertThat(pending.verify(valid)).isFalse()
        assertThat(pending.toString()).doesNotContain(replyKey.joinToString())
    }

    @Test fun attackerControlledContextAndDigestCannotClaimOriginalRequest() {
        val original = probe()
        val pending = guard(original, maxFailures = 5)
        assertThat(pending.verify(seal(original, tv = altered(tvFingerprint)))).isFalse()
        assertThat(pending.verify(seal(original, phone = altered(phoneFingerprint)))).isFalse()
        assertThat(pending.verify(seal(original, digest = ByteArray(32) { 9 }))).isFalse()
        assertThat(pending.verify(seal(original, counter = 2))).isFalse()
        assertThat(pending.isPending()).isTrue()
        assertThat(pending.verify(seal(original))).isTrue()
    }

    @Test fun tamperedCiphertextAndIvFailAuthentication() {
        val original = probe()
        val reply = seal(original)
        val body = Lc04TypedWire.decodeReceiptFrame(reply)!!
        val pending = guard(original)
        val damaged = Lc04ReceiptBody(
            body.counter, body.requestDigest(), body.iv(), altered(body.ciphertext()),
        )
        val alteredFrame = Lc04WireFrame(
            Lc04WireKind.SEALED_TV_RECEIPT, invitation, pairing, tvFingerprint,
            phoneFingerprint, Lc04TypedWire.encodeReceipt(damaged),
        )
        assertThat(pending.verify(alteredFrame)).isFalse()
        // Alter the transmitted IV while retaining the *original* GCM tag.
        // A legitimate TV may choose a different IV, so merely changing the IV
        // during encryption would not be an attack.
        val forgedIvBody = Lc04ReceiptBody(body.counter, body.requestDigest(),
            ByteArray(12) { 1 }, body.ciphertext())
        val forgedIvFrame = Lc04WireFrame(Lc04WireKind.SEALED_TV_RECEIPT,
            invitation, pairing, tvFingerprint, phoneFingerprint,
            Lc04TypedWire.encodeReceipt(forgedIvBody))
        assertThat(pending.verify(forgedIvFrame)).isFalse()
        assertThat(pending.verify(reply)).isTrue()
    }

    @Test fun replayFailureBudgetPermanentlyRevokesCurrentRequest() {
        val original = probe()
        val pending = guard(original, maxFailures = 2)
        val unrelated = seal(original, digest = ByteArray(32))
        assertThat(pending.verify(unrelated)).isFalse()
        assertThat(pending.isPending()).isTrue()
        assertThat(pending.verify(unrelated)).isFalse()
        assertThat(pending.isPending()).isFalse()
        assertThat(pending.verify(seal(original))).isFalse()
    }

    @Test fun tvProofWithIncorrectSecretOrChallengeIsNeverAccepted() {
        val original = probe()
        val pending = guard(original)
        assertThat(pending.verify(seal(original, secret = ByteArray(32) { 42 }))).isFalse()
        assertThat(pending.verify(seal(original, confirmedChallenge = ByteArray(16) { 42 })))
            .isFalse()
        assertThat(pending.verify(seal(original))).isTrue()
    }

    @Test fun monotonicDeadlineAndClockRollbackRevokePendingState() {
        val original = probe()
        var now = 10_000L
        val pending = guard(original, now = { now }, ttl = 1_000)
        now = 10_999L
        assertThat(pending.isPending()).isTrue()
        now = 11_000L
        assertThat(pending.verify(seal(original))).isFalse()
        now = 10_100L
        assertThat(pending.isPending()).isFalse()

        var rollback = 10_000L
        val second = guard(original, now = { rollback })
        rollback = 9_999
        assertThat(second.isPending()).isFalse()
        rollback = 10_001
        assertThat(second.verify(seal(original))).isFalse()
    }

    @Test fun manualCloseIsPermanentAndInputKeysAreDefensivelyCopied() {
        val original = probe()
        val temporaryKey = replyKey.clone()
        val temporaryChallenge = challenge.clone()
        val pending = guard(original, key = temporaryKey, nonce = temporaryChallenge)
        temporaryKey.fill(0)
        temporaryChallenge.fill(0)
        assertThat(pending.verify(seal(original))).isTrue()

        val another = guard(original)
        another.close()
        assertThat(another.isPending()).isFalse()
        assertThat(another.verify(seal(original))).isFalse()
    }

    @Test fun invalidReplyKeyNonceOrRequestAreRejectedOnConstruction() {
        val valid = probe()
        var rejected = 0
        for ((key, nonce) in listOf(
            ByteArray(31) to challenge,
            replyKey to ByteArray(15),
            ByteArray(33) to challenge,
            replyKey to ByteArray(17),
        )) {
            try {
                guard(valid, key = key, nonce = nonce)
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }
        assertThat(rejected).isEqualTo(4)
        val receipt = seal(valid)
        var badRequestRejected = false
        try {
            guard(receipt)
        } catch (_: IllegalArgumentException) {
            badRequestRejected = true
        }
        assertThat(badRequestRejected).isTrue()
    }

    private fun altered(source: ByteArray): ByteArray = source.clone().also {
        it[0] = (it[0].toInt() xor 1).toByte()
    }
}

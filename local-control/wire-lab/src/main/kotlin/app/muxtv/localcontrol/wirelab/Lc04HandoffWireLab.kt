package app.muxtv.localcontrol.wirelab

import java.security.MessageDigest

/**
 * LC04-S2D research ONLY: separate MXH1 handshake framing, never an MXW1
 * extension. Decoding authenticates NOTHING. In particular, a structurally
 * matching TV_CONFIRM is NOT evidence of TV identity or possession of the
 * HPKE seed. No Android, TCP, HTTP, provider or source operation may call this.
 *
 * Big-endian v1 header, 138 bytes:
 *   0 magic MXH1[4]; 4 version[1]; 5 kind[1];
 *   6 invitation[16]; 22 pairing[16]; 38 TV HPKE fingerprint[32];
 *   70 approved phone Ed25519 fingerprint[32]; 102 signed-offer digest[32];
 *   134 counter[u16]; 136 body length[u16]; 138 body.
 *
 * SIGNED_SEED_OFFER's digest is 32 zero bytes because it hashes the complete
 * signed offer itself (including its signature). Each confirmation MUST carry
 * SHA-256 of that exact encoded signed offer. This is a public digest, not an
 * access token. Domain, kind, identities, counter, signature width and HPKE
 * bytes are part of the detached Ed25519 signing statement.
 */
enum class Lc04HandoffKind(val id: Int, val min: Int, val max: Int) {
    SIGNED_SEED_OFFER(0x11, 84, 2120),
    TV_KEY_CONFIRM(0x12, 64, 64),
    PHONE_KEY_CONFIRM(0x13, 32, 32),
    // Authenticated TV acceptance of the exact PHONE_KEY_CONFIRM frame.
    TV_FINISHED(0x14, 32, 32);

    companion object {
        fun fromId(id: Int): Lc04HandoffKind? = entries.firstOrNull { it.id == id }
    }
}

class Lc04HandoffFrame(
    val kind: Lc04HandoffKind,
    tvInvitationId: ByteArray,
    tvPairingId: ByteArray,
    tvHpkeFingerprint: ByteArray,
    phoneEd25519Fingerprint: ByteArray,
    exactSignedOfferDigest: ByteArray,
    val counter: Int,
    payload: ByteArray,
) {
    private val invitation = tvInvitationId.clone()
    private val pairing = tvPairingId.clone()
    private val tv = tvHpkeFingerprint.clone()
    private val phone = phoneEd25519Fingerprint.clone()
    private val offerDigest = exactSignedOfferDigest.clone()
    private val body = payload.clone()

    init {
        require(invitation.size == 16 && pairing.size == 16)
        require(tv.size == 32 && phone.size == 32 && offerDigest.size == 32)
        require(counter in 1..128)
        require(body.size in kind.min..kind.max)
    }

    fun invitation(): ByteArray = invitation.clone()
    fun pairing(): ByteArray = pairing.clone()
    fun tv(): ByteArray = tv.clone()
    fun phone(): ByteArray = phone.clone()
    fun offerDigest(): ByteArray = offerDigest.clone()
    fun payload(): ByteArray = body.clone()

    override fun toString(): String = "Lc04HandoffFrame(kind=${kind.name}, [REDACTED])"
}

object Lc04HandoffWireLab {
    const val HEADER_BYTES = 138
    const val MAX_PAYLOAD_BYTES = 2120
    const val MAX_FRAME_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES

    private val MAGIC = byteArrayOf(0x4d, 0x58, 0x48, 0x31) // MXH1
    private const val VERSION = 1
    private val SIGN_DOMAIN =
        "MuxTV-LC04-MXH1-SIGNED-SEED-OFFER-v1|".toByteArray(Charsets.US_ASCII)

    /**
     * Body format: [ciphertextLength:2][HPKE ciphertext:17..2048]
     * [signatureLength:1][Tink Ed25519 signature:64 or 69].
     *
     * The public cipher bytes here MUST ultimately be encrypted with a
     * distinct HPKE context, never the existing MXW1 CHANNEL_PROBE domain.
     * Verifying signatures and TV-local consent belongs to a future owner.
     */
    fun signedOfferBody(ciphertext: ByteArray, signature: ByteArray): ByteArray {
        require(ciphertext.size in 17..2048)
        require(signature.size == 64 || signature.size == 69)
        return ByteArray(2 + ciphertext.size + 1 + signature.size).also {
            it[0] = (ciphertext.size ushr 8).toByte()
            it[1] = ciphertext.size.toByte()
            ciphertext.copyInto(it, 2)
            it[2 + ciphertext.size] = signature.size.toByte()
            signature.copyInto(it, 3 + ciphertext.size)
        }
    }

    fun decodeSignedOfferBody(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (payload.size !in 84..2120) return null
        val cipherLength = u16(payload, 0)
        if (cipherLength !in 17..2048) return null
        val offset = 2 + cipherLength
        if (offset >= payload.size) return null
        val sigLength = payload[offset].toInt() and 0xff
        if (sigLength != 64 && sigLength != 69) return null
        if (payload.size != offset + 1 + sigLength) return null
        return payload.copyOfRange(2, offset) to
            payload.copyOfRange(offset + 1, payload.size)
    }

    fun tvConfirmationBody(freshTvNonce: ByteArray, hmacTag: ByteArray): ByteArray {
        require(freshTvNonce.size == 32 && hmacTag.size == 32)
        return freshTvNonce + hmacTag
    }

    fun encode(frame: Lc04HandoffFrame): ByteArray {
        val body = frame.payload()
        require(canonical(frame, body))
        val result = ByteArray(HEADER_BYTES + body.size)
        MAGIC.copyInto(result, 0)
        result[4] = VERSION.toByte()
        result[5] = frame.kind.id.toByte()
        frame.invitation().copyInto(result, 6)
        frame.pairing().copyInto(result, 22)
        frame.tv().copyInto(result, 38)
        frame.phone().copyInto(result, 70)
        frame.offerDigest().copyInto(result, 102)
        writeU16(result, 134, frame.counter)
        writeU16(result, 136, body.size)
        body.copyInto(result, HEADER_BYTES)
        body.fill(0)
        return result
    }

    /** Pure structural canonicality only; never claim authentication. */
    fun decode(wire: ByteArray): Lc04HandoffFrame? {
        if (wire.size !in HEADER_BYTES..MAX_FRAME_BYTES) return null
        for (i in MAGIC.indices) if (wire[i] != MAGIC[i]) return null
        if (wire[4].toInt() != VERSION) return null
        val kind = Lc04HandoffKind.fromId(wire[5].toInt() and 0xff) ?: return null
        val counter = u16(wire, 134)
        val bodyLength = u16(wire, 136)
        if (counter !in 1..128 || bodyLength !in kind.min..kind.max ||
            wire.size != HEADER_BYTES + bodyLength
        ) return null
        val frame = Lc04HandoffFrame(
            kind, wire.copyOfRange(6, 22), wire.copyOfRange(22, 38),
            wire.copyOfRange(38, 70), wire.copyOfRange(70, 102),
            wire.copyOfRange(102, 134), counter,
            wire.copyOfRange(HEADER_BYTES, wire.size),
        )
        val payload = frame.payload()
        return if (canonical(frame, payload)) frame else null
    }

    private fun canonical(frame: Lc04HandoffFrame, body: ByteArray): Boolean {
        val digest = frame.offerDigest()
        val zero = digest.all { it == 0.toByte() }
        digest.fill(0)
        if (body.size !in frame.kind.min..frame.kind.max) return false
        return when (frame.kind) {
            Lc04HandoffKind.SIGNED_SEED_OFFER ->
                zero && decodeSignedOfferBody(body)?.let { (ciphertext, signature) ->
                    ciphertext.fill(0)
                    signature.fill(0)
                    true
                } == true
            Lc04HandoffKind.TV_KEY_CONFIRM,
            Lc04HandoffKind.PHONE_KEY_CONFIRM,
            Lc04HandoffKind.TV_FINISHED -> !zero
        }
    }

    /**
     * Signature statement excludes only the signature BYTES. It includes
     * the exact canonical header, ciphertext, and signature width, so
     * switching RAW(64) <-> TINK(69) is not a valid no-op.
     */
    fun offerSigningStatement(encodedOffer: ByteArray): ByteArray? {
        val offer = decode(encodedOffer) ?: return null
        if (offer.kind != Lc04HandoffKind.SIGNED_SEED_OFFER) return null
        val payload = offer.payload()
        val ciphertextLength = u16(payload, 0)
        val bodyPrefixSize = 2 + ciphertextLength
        val signedPrefix = encodedOffer.copyOfRange(0, HEADER_BYTES + bodyPrefixSize)
        return SIGN_DOMAIN + signedPrefix + byteArrayOf(
            payload[bodyPrefixSize],
        )
    }

    fun digestOfSignedOffer(encodedOffer: ByteArray): ByteArray? {
        val frame = decode(encodedOffer) ?: return null
        if (frame.kind != Lc04HandoffKind.SIGNED_SEED_OFFER) return null
        return MessageDigest.getInstance("SHA-256").digest(encodedOffer)
    }

    /**
     * Verifies only exact byte-level binding, NOT HMAC, Ed25519, HPKE,
     * expiry, replay state, TV consent or any source operation.
     */
    fun structurallyMatchesOffer(
        encodedOffer: ByteArray,
        encodedConfirmation: ByteArray,
        expectedKind: Lc04HandoffKind,
    ): Boolean {
        if (expectedKind == Lc04HandoffKind.SIGNED_SEED_OFFER) return false
        val offer = decode(encodedOffer) ?: return false
        val confirm = decode(encodedConfirmation) ?: return false
        if (offer.kind != Lc04HandoffKind.SIGNED_SEED_OFFER ||
            confirm.kind != expectedKind || offer.counter != confirm.counter
        ) return false
        val digest = digestOfSignedOffer(encodedOffer) ?: return false
        return try {
            MessageDigest.isEqual(confirm.offerDigest(), digest) &&
                MessageDigest.isEqual(confirm.invitation(), offer.invitation()) &&
                MessageDigest.isEqual(confirm.pairing(), offer.pairing()) &&
                MessageDigest.isEqual(confirm.tv(), offer.tv()) &&
                MessageDigest.isEqual(confirm.phone(), offer.phone())
        } finally { digest.fill(0) }
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)

    private fun writeU16(bytes: ByteArray, index: Int, value: Int) {
        require(value in 0..65535)
        bytes[index] = (value ushr 8).toByte()
        bytes[index + 1] = value.toByte()
    }
}

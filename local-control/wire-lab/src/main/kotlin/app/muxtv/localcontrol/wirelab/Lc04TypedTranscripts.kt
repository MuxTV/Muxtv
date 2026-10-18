package app.muxtv.localcontrol.wirelab

/**
 * LC04-S3-D1B research only: strictly typed payloads and unambiguous crypto inputs.
 *
 * A parsed frame is NEVER an authorization token. The caller must verify its Ed25519
 * signature or AES-GCM tag with the key bound to the *physically scanned TV QR*,
 * and then apply TV-local consent, monotonic counters and receiver expiration.
 *
 * No network I/O, private key material, SOURCE_* commands, user data or IPTV URLs.
 */
class Lc04ProbeBody(
    val counter: Int,
    ciphertext: ByteArray,
    signature: ByteArray,
) {
    private val encrypted = ciphertext.clone()
    private val signed = signature.clone()
    init {
        require(counter in 1..128)
        require(encrypted.size in 17..MAX_CIPHERTEXT)
        require(signed.size == 64 || signed.size == 69) // Ed25519 RAW or Tink-prefixed
    }
    fun ciphertext(): ByteArray = encrypted.clone()
    fun signature(): ByteArray = signed.clone()
    override fun toString(): String = "Lc04ProbeBody(counter=$counter, [REDACTED])"
    companion object { const val MAX_CIPHERTEXT = 4022 }
}

class Lc04ReceiptBody(
    val counter: Int,
    requestDigest: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val request = requestDigest.clone()
    private val nonce = iv.clone()
    private val sealed = ciphertext.clone()
    init {
        require(counter in 1..128)
        require(request.size == 32)
        require(nonce.size == 12)
        require(sealed.size in 17..MAX_CIPHERTEXT)
    }
    fun requestDigest(): ByteArray = request.clone()
    fun iv(): ByteArray = nonce.clone()
    fun ciphertext(): ByteArray = sealed.clone()
    override fun toString(): String = "Lc04ReceiptBody(counter=$counter, [REDACTED])"
    companion object { const val MAX_CIPHERTEXT = 4048 }
}

/**
 * Canonical typed codecs. Public constructors reject noncanonical input lengths;
 * decoders reject all unknown modes, truncation and extra tail bytes.
 *
 * Probe payload: counter u16 BE | ciphertext_len u16 BE | ciphertext |
 *                signature_len u8 | Ed25519 signature (64 or 69 bytes).
 *
 * Receipt payload: counter u16 BE | request_digest[32] | AES-GCM IV[12] |
 *                  ciphertext_len u16 BE | ciphertext+tag (17..4048 bytes).
 *
 * The exact u16 fields use no alternative encoding or optional extensions.
 */
object Lc04TypedWire {
    fun encodeProbe(body: Lc04ProbeBody): ByteArray {
        val cipher = body.ciphertext()
        val signature = body.signature()
        val out = ByteArray(5 + cipher.size + signature.size)
        writeU16(out, 0, body.counter)
        writeU16(out, 2, cipher.size)
        cipher.copyInto(out, 4)
        out[4 + cipher.size] = signature.size.toByte()
        signature.copyInto(out, 5 + cipher.size)
        return out
    }

    fun decodeProbe(payload: ByteArray): Lc04ProbeBody? {
        if (payload.size !in 5 + 17 + 64..4096) return null
        val counter = readU16(payload, 0)
        val cipherLength = readU16(payload, 2)
        if (counter !in 1..128 || cipherLength !in 17..Lc04ProbeBody.MAX_CIPHERTEXT) return null
        val signatureLengthIndex = 4 + cipherLength
        if (signatureLengthIndex >= payload.size) return null
        val signatureLength = payload[signatureLengthIndex].toInt() and 0xff
        if (signatureLength != 64 && signatureLength != 69) return null
        if (5 + cipherLength + signatureLength != payload.size) return null
        return Lc04ProbeBody(
            counter,
            payload.copyOfRange(4, signatureLengthIndex),
            payload.copyOfRange(signatureLengthIndex + 1, payload.size),
        )
    }

    fun encodeReceipt(body: Lc04ReceiptBody): ByteArray {
        val cipher = body.ciphertext()
        val out = ByteArray(48 + cipher.size)
        writeU16(out, 0, body.counter)
        body.requestDigest().copyInto(out, 2)
        body.iv().copyInto(out, 34)
        writeU16(out, 46, cipher.size)
        cipher.copyInto(out, 48)
        return out
    }

    fun decodeReceipt(payload: ByteArray): Lc04ReceiptBody? {
        if (payload.size !in 48 + 17..4096) return null
        val counter = readU16(payload, 0)
        val cipherLength = readU16(payload, 46)
        if (counter !in 1..128 || cipherLength !in 17..Lc04ReceiptBody.MAX_CIPHERTEXT) return null
        if (48 + cipherLength != payload.size) return null
        return Lc04ReceiptBody(
            counter, payload.copyOfRange(2, 34), payload.copyOfRange(34, 46),
            payload.copyOfRange(48, payload.size),
        )
    }

    fun decodeProbeFrame(frame: Lc04WireFrame): Lc04ProbeBody? =
        if (frame.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE) decodeProbe(frame.payload()) else null

    fun decodeReceiptFrame(frame: Lc04WireFrame): Lc04ReceiptBody? =
        if (frame.kind == Lc04WireKind.SEALED_TV_RECEIPT) decodeReceipt(frame.payload()) else null

    private fun writeU16(out: ByteArray, i: Int, value: Int) {
        require(value in 0..65535)
        out[i] = (value ushr 8).toByte()
        out[i + 1] = value.toByte()
    }

    private fun readU16(input: ByteArray, i: Int): Int =
        ((input[i].toInt() and 255) shl 8) or (input[i + 1].toInt() and 255)
}

/**
 * Domain-separated, binary (not string-joined) canonical input to crypto.
 * These functions only CONSTRUCT inputs, they neither sign nor verify.
 *
 * To avoid a signature circularity, probeSigningStatement excludes the signature
 * but binds the complete canonical HPKE ciphertext, verified TV/phone fingerprints,
 * context IDs, fixed message kind and monotonic request counter.
 *
 * ReceiptAad excludes receipt ciphertext (AES-GCM binds that itself), and binds
 * the original *signed envelope digest*, counter, both identities and direction.
 */
object Lc04TypedTranscript {
    private val probeDomain = "MuxTV-LC04-PHONE-TO-TV-PROBE-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val replyDomain = "MuxTV-LC04-TV-TO-PHONE-RECEIPT-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val marker = byteArrayOf(0x4d, 0x58, 0x57, 0x31, 1) // MXW1 + version

    fun probeSigningStatement(frame: Lc04WireFrame, body: Lc04ProbeBody): ByteArray {
        require(frame.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE)
        val parsed = Lc04TypedWire.decodeProbeFrame(frame)
        require(parsed != null && encodedEquivalentProbe(body, parsed)) {
            "Probe statement requires exact frame/body matching."
        }
        val cipher = body.ciphertext()
        val content = ByteArray(4 + cipher.size)
        content[0] = (body.counter ushr 8).toByte()
        content[1] = body.counter.toByte()
        content[2] = (cipher.size ushr 8).toByte()
        content[3] = cipher.size.toByte()
        cipher.copyInto(content, 4)
        return probeDomain + commonContext(frame) + content
    }

    fun receiptAad(frame: Lc04WireFrame, body: Lc04ReceiptBody): ByteArray {
        require(frame.kind == Lc04WireKind.SEALED_TV_RECEIPT)
        val parsed = Lc04TypedWire.decodeReceiptFrame(frame)
        require(parsed != null && encodedEquivalentReceipt(body, parsed)) {
            "Receipt AAD requires exact frame/body matching."
        }
        return replyDomain + commonContext(frame) +
            byteArrayOf((body.counter ushr 8).toByte(), body.counter.toByte()) +
            body.requestDigest()
    }

    private fun commonContext(frame: Lc04WireFrame): ByteArray =
        marker + byteArrayOf(frame.kind.wireId.toByte()) +
            frame.tvInvitationId() + frame.tvPairingId() +
            frame.tvHpkeFingerprint() + frame.phoneKeyFingerprint()

    private fun encodedEquivalentProbe(a: Lc04ProbeBody, b: Lc04ProbeBody): Boolean =
        a.counter == b.counter &&
            Lc04TypedWire.encodeProbe(a).contentEquals(Lc04TypedWire.encodeProbe(b))

    private fun encodedEquivalentReceipt(a: Lc04ReceiptBody, b: Lc04ReceiptBody): Boolean =
        a.counter == b.counter &&
            Lc04TypedWire.encodeReceipt(a).contentEquals(Lc04TypedWire.encodeReceipt(b))
}

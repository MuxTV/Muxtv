package app.muxtv.localcontrol.wirelab

/**
 * EXPERIMENTAL, non-network framing for LC04 enrollment and signed HPKE acknowledgements.
 * A well-formed frame is NOT authenticated and is NOT a source-management capability.
 *
 * Big-endian, no strings, no variable-width numbers, no unknown flags or trailing bytes.
 * The outer framing is intentionally independent of provider URLs, names and credentials.
 *
 * Layout:
 *   magic[4] = MXW1, version[1] = 1, kind[1],
 *   tvInvitationId[16], tvPairingId[16], tvHpkeSha256[32], phoneKeySha256[32],
 *   payloadLength[2] (unsigned big endian), payload[payloadLength]
 * Total header size: 104 bytes. Maximum frame: 4200 bytes.
 *
 * Fields are untrusted until separately authenticated by the signed HPKE
 * transcript/TV-verified enrollment challenge. Never use the IDs as bearers.
 */
enum class Lc04WireKind(val wireId: Int, val minPayload: Int, val maxPayload: Int) {
    PHONE_PUBLIC_KEY(1, 1, 256),
    TV_CHALLENGE(2, 32, 32),
    PHONE_PROOF(3, 64, 69),
    SIGNED_CHANNEL_PROBE(4, 40, 4_096),
    SEALED_TV_RECEIPT(5, 29, 4_096);

    companion object {
        fun fromId(id: Int): Lc04WireKind? = entries.firstOrNull { it.wireId == id }
    }
}

class Lc04WireFrame(
    val kind: Lc04WireKind,
    tvInvitationId: ByteArray,
    tvPairingId: ByteArray,
    tvHpkeFingerprint: ByteArray,
    phoneKeyFingerprint: ByteArray,
    payload: ByteArray,
) {
    private val invitation = tvInvitationId.clone()
    private val pairing = tvPairingId.clone()
    private val tvKey = tvHpkeFingerprint.clone()
    private val phoneKey = phoneKeyFingerprint.clone()
    private val body = payload.clone()

    init {
        require(invitation.size == 16) { "Invalid TV invitation length." }
        require(pairing.size == 16) { "Invalid TV pairing length." }
        require(tvKey.size == 32) { "Invalid TV fingerprint length." }
        require(phoneKey.size == 32) { "Invalid phone fingerprint length." }
        require(body.size in kind.minPayload..kind.maxPayload) {
            "Invalid payload length for frame kind."
        }
    }

    fun tvInvitationId(): ByteArray = invitation.clone()
    fun tvPairingId(): ByteArray = pairing.clone()
    fun tvHpkeFingerprint(): ByteArray = tvKey.clone()
    fun phoneKeyFingerprint(): ByteArray = phoneKey.clone()
    fun payload(): ByteArray = body.clone()

    override fun toString(): String = "Lc04WireFrame(kind=${kind.name}, bytes=[REDACTED])"
}

/**
 * Decode validates CANONICAL framing ONLY. No network I/O and no authentication.
 *
 * Do not add source operation types or any JSON/string interpretation to this codec.
 * Sensitive IPTV parameters must never be fed to it until an independently reviewed
 * end-to-end encrypted application channel exists.
 */
object Lc04WireLab {
    const val HEADER_BYTES = 104
    const val MAX_PAYLOAD_BYTES = 4_096
    const val MAX_FRAME_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES
    private val magic = byteArrayOf(0x4d, 0x58, 0x57, 0x31) // MXW1; laboratory only.
    private const val VERSION = 1

    fun encode(frame: Lc04WireFrame): ByteArray {
        val body = frame.payload()
        check(body.size in frame.kind.minPayload..frame.kind.maxPayload)
        val bytes = ByteArray(HEADER_BYTES + body.size)
        magic.copyInto(bytes, 0)
        bytes[4] = VERSION.toByte()
        bytes[5] = frame.kind.wireId.toByte()
        frame.tvInvitationId().copyInto(bytes, 6)
        frame.tvPairingId().copyInto(bytes, 22)
        frame.tvHpkeFingerprint().copyInto(bytes, 38)
        frame.phoneKeyFingerprint().copyInto(bytes, 70)
        bytes[102] = (body.size ushr 8).toByte()
        bytes[103] = body.size.toByte()
        body.copyInto(bytes, HEADER_BYTES)
        body.fill(0)
        return bytes
    }

    /** Untrusted input: return null for all malformed frames, without scanning for magic. */
    fun decode(bytes: ByteArray): Lc04WireFrame? {
        if (bytes.size !in HEADER_BYTES..MAX_FRAME_BYTES) return null
        for (i in magic.indices) {
            if (bytes[i] != magic[i]) return null
        }
        if (bytes[4].toInt() != VERSION) return null
        val kind = Lc04WireKind.fromId(bytes[5].toInt() and 0xff) ?: return null
        val length = ((bytes[102].toInt() and 0xff) shl 8) or (bytes[103].toInt() and 0xff)
        if (length !in kind.minPayload..kind.maxPayload) return null
        if (HEADER_BYTES + length != bytes.size) return null // reject trailing/dangling bytes
        return Lc04WireFrame(
            kind = kind,
            tvInvitationId = bytes.copyOfRange(6, 22),
            tvPairingId = bytes.copyOfRange(22, 38),
            tvHpkeFingerprint = bytes.copyOfRange(38, 70),
            phoneKeyFingerprint = bytes.copyOfRange(70, 102),
            payload = bytes.copyOfRange(HEADER_BYTES, bytes.size),
        )
    }
}

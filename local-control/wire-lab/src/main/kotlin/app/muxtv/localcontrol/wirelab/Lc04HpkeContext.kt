package app.muxtv.localcontrol.wirelab

/**
 * Native Android-compatible HPKE contextInfo for a CHANNEL_PROBE only.
 * The context authenticates direction, version, public TV QR identifiers,
 * pinned TV HPKE fingerprint, approved phone fingerprint and request counter.
 *
 * This is never a SOURCE_* operation, grant, LAN address or bearer.
 */
object Lc04HpkeContext {
    private val domain = "MuxTV-LC04-HPKE-CHANNEL-PROBE-v1\u0000"
        .toByteArray(Charsets.US_ASCII)

    fun forProbe(frame: Lc04WireFrame, counter: Int): ByteArray {
        require(frame.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE)
        require(counter in 1..128)
        return domain +
            byteArrayOf(0x4d, 0x58, 0x57, 0x31, 1, frame.kind.wireId.toByte()) +
            frame.tvInvitationId() + frame.tvPairingId() +
            frame.tvHpkeFingerprint() + frame.phoneKeyFingerprint() +
            byteArrayOf((counter ushr 8).toByte(), counter.toByte())
    }
}

package app.muxtv.localcontrol.wirelab

/**
 * Dedicated signed MXH1 offer HPKE info. This is NOT the MXW1 receipt
 * context. All fields are canonical, public and fixed-size. No source inputs.
 */
object Lc04MxH1HpkeContext {
    private val DOMAIN =
        "MuxTV-LC04-MXH1-HPKE-SEED-HANDOFF-v1\u0000".toByteArray(Charsets.US_ASCII)

    fun forOffer(frame: Lc04HandoffFrame): ByteArray {
        require(frame.kind == Lc04HandoffKind.SIGNED_SEED_OFFER)
        require(frame.counter in 1..128)
        return DOMAIN + byteArrayOf(0x4d, 0x58, 0x48, 0x31, 1, 0x11) +
            frame.invitation() + frame.pairing() + frame.tv() + frame.phone() +
            byteArrayOf((frame.counter ushr 8).toByte(), frame.counter.toByte())
    }
}

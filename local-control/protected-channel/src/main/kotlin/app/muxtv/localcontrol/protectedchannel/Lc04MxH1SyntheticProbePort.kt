package app.muxtv.localcontrol.protectedchannel

/**
 * LC04-S2J2A: intentionally tiny synthetic-only, fail-closed record surface.
 *
 * This does NOT authenticate a QR or verify TV_FINISHED. Only a caller that
 * independently completed the original signed HPKE/Ed25519/MXH1 proof may
 * transfer a derived key owner here. There is no arbitrary plaintext, source
 * URL, credential API, key getter or persistent pairing authority.
 *
 * close() is terminal and must be called by the original QR/session owner
 * upon TV Activity stop, route change, expiry, timeout or cancellation.
 */
interface Lc04MxH1SyntheticProbePort : AutoCloseable {
    /** Fresh internally generated, fixed-domain 32-byte synthetic probe only. */
    fun sealSyntheticProbe(): ByteArray?

    /** True only for an authentic exactly-next synthetic probe; false is terminal. */
    fun acceptSyntheticProbe(wire: ByteArray): Boolean

    override fun close()
}

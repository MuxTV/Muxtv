package app.muxtv.localcontrol.protectedchannel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LC04-S2J1: a post-MXH1 *synthetic-only* AEAD record contract.
 *
 * This is NOT a production phone-to-TV data plane: it cannot accept a caller's
 * plaintext, source URL, username, password, bearer or action. The only possible
 * plaintext is a newly generated 32-byte probe with a fixed domain marker.
 *
 * An actual owner of authenticated MXH1-derived keys must explicitly transfer
 * exclusive ownership here. The factory itself does NOT authenticate a handshake.
 * No TCP, UI, Android permissions, source backend or persistent grant is exposed.
 */
enum class Lc04SyntheticRecordRole { PHONE, TV }

class Lc04MxH1SyntheticSealedRecords private constructor(
    private val keys: Lc04SyntheticDerivedKeys,
    private val role: Lc04SyntheticRecordRole,
    private val clock: () -> Long,
    private val createdAt: Long,
    bindingDigest: ByteArray,
    private val random: SecureRandom,
) : AutoCloseable {
    private val binding = bindingDigest.clone()
    private var lastObserved = createdAt
    private var sent = 0
    private var received = 0
    private var closed = false

    /**
     * The wire is fixed at 104 bytes. Header and ciphertext are authenticated
     * together. The 96-bit GCM nonce is a deterministic, strictly increasing
     * per-direction counter (8 zero bytes || u32 counter), safe only because
     * each key owner is claimable once and HKDF-derived traffic keys are unique
     * per independently authenticated, fresh MXH1 offer/TV nonce.
     */
    @Synchronized
    fun sealSyntheticProbe(): ByteArray? {
        if (!alive() || sent >= MAX_RECORDS) return failSeal()
        val sequence = sent + 1
        val direction = if (role == Lc04SyntheticRecordRole.PHONE) PHONE_TO_TV else TV_TO_PHONE
        val nonce = nonceFor(sequence)
        val header = header(direction, sequence, nonce)
        val aad = AAD_DOMAIN + header
        val plain = ByteArray(PROBE_BYTES).also(random::nextBytes)
        MARKER.copyInto(plain)
        return try {
            val sealed = keys.sealSyntheticLabRecord(
                direction == PHONE_TO_TV, nonce, plain, aad,
            ) ?: return failSeal()
            if (sealed.size != CIPHERTEXT_BYTES) return failSeal()
            sent = sequence
            header + sealed
        } finally {
            nonce.fill(0)
            header.fill(0)
            aad.fill(0)
            plain.fill(0)
        }
    }

    /** Any invalid, replayed, reflected, out-of-order or forged record closes both keys. */
    @Synchronized
    fun acceptSyntheticProbe(wire: ByteArray): Boolean {
        if (!alive() || received >= MAX_RECORDS) return reject()
        if (wire.size != WIRE_BYTES) return reject()
        // Snapshot untrusted bytes once; no mutation between validation and AEAD.
        val frame = wire.clone()
        val header = frame.copyOfRange(0, HEADER_BYTES)
        try {
            if (!MessageDigest.isEqual(header.copyOfRange(0, 4), MAGIC) ||
                header[4] != VERSION.toByte()
            ) return reject()
            val direction = if (role == Lc04SyntheticRecordRole.PHONE) TV_TO_PHONE else PHONE_TO_TV
            if ((header[5].toInt() and 0xff) != direction) return reject()
            val peerBinding = header.copyOfRange(6, 38)
            val bindingMatches = try {
                MessageDigest.isEqual(binding, peerBinding)
            } finally { peerBinding.fill(0) }
            if (!bindingMatches) return reject()

            val sequence = ByteBuffer.wrap(header, 38, 4)
                .order(ByteOrder.BIG_ENDIAN).int
            if (sequence != received + 1 || sequence !in 1..MAX_RECORDS) return reject()
            val ciphertextLength = ByteBuffer.wrap(header, 42, 2)
                .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
            if (ciphertextLength != CIPHERTEXT_BYTES) return reject()

            val nonce = header.copyOfRange(44, HEADER_BYTES)
            val expectedNonce = nonceFor(sequence)
            val nonceMatches = try {
                MessageDigest.isEqual(nonce, expectedNonce)
            } finally {
                nonce.fill(0)
                expectedNonce.fill(0)
            }
            if (!nonceMatches) return reject()

            val ciphertext = frame.copyOfRange(HEADER_BYTES, WIRE_BYTES)
            val aad = AAD_DOMAIN + header
            val plain = try {
                keys.openSyntheticLabRecord(direction == PHONE_TO_TV, nonceFor(sequence),
                    ciphertext, aad)
            } finally {
                ciphertext.fill(0)
                aad.fill(0)
            } ?: return reject()
            val matches = try {
                plain.size == PROBE_BYTES && MessageDigest.isEqual(
                    plain.copyOfRange(0, MARKER.size), MARKER,
                )
            } finally { plain.fill(0) }
            if (!matches) return reject()
            received = sequence
            return true
        } finally {
            frame.fill(0)
            header.fill(0)
        }
    }

    private fun header(direction: Int, sequence: Int, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(VERSION.toByte()).put(direction.toByte())
            .put(binding).putInt(sequence).putShort(CIPHERTEXT_BYTES.toShort())
            .put(nonce).array()

    private fun nonceFor(sequence: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putLong(0L).putInt(sequence).array()

    private fun alive(): Boolean {
        if (closed) return false
        val now = clock()
        if (now < lastObserved || now < createdAt || now - createdAt >= TTL_MILLIS) {
            close()
            return false
        }
        lastObserved = now
        return true
    }

    private fun failSeal(): ByteArray? { close(); return null }
    private fun reject(): Boolean { close(); return false }

    /** Closing the record owner consumes all traffic and confirmation keys. */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        sent = MAX_RECORDS
        received = MAX_RECORDS
        binding.fill(0)
        keys.close()
    }

    override fun toString(): String = "Lc04MxH1SyntheticSealedRecords([REDACTED])"

    companion object {
        private val MAGIC = byteArrayOf(0x4d, 0x58, 0x52, 0x31) // MXR1
        private val MARKER = "MuxTV-S2J-PROBE".toByteArray(Charsets.US_ASCII)
        private val AAD_DOMAIN = "MuxTV-LC04-MXR1-SYNTHETIC-AAD-v1\u0000"
            .toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val PHONE_TO_TV = 1
        private const val TV_TO_PHONE = 2
        private const val PROBE_BYTES = 32
        private const val CIPHERTEXT_BYTES = PROBE_BYTES + 16 // AES-GCM 128-bit tag
        private const val HEADER_BYTES = 56
        private const val WIRE_BYTES = HEADER_BYTES + CIPHERTEXT_BYTES
        private const val MAX_RECORDS = 4
        private const val TTL_MILLIS = 120_000L

        /**
         * Lab factory, intentionally NOT named "fromAuthenticatedHandshake".
         * The caller must prove QR/Ed25519/HPKE/TV_FINISHED separately.
         * One derived-key owner can create only ONE channel for its lifetime,
         * avoiding counter reuse on reopen with the same AES-GCM key.
         */
        fun fromDerivedLabKeys(
            keys: Lc04SyntheticDerivedKeys,
            role: Lc04SyntheticRecordRole,
            clock: () -> Long,
            random: SecureRandom = SecureRandom(),
        ): Lc04MxH1SyntheticSealedRecords {
            val created = clock()
            require(created >= 0L && created <= Long.MAX_VALUE - TTL_MILLIS)
            val binding = requireNotNull(keys.claimSyntheticRecordLabOwner()) {
                "Derived key owner is closed or already claimed."
            }
            return try {
                Lc04MxH1SyntheticSealedRecords(keys, role, clock, created, binding, random)
            } finally { binding.fill(0) }
        }
    }
}

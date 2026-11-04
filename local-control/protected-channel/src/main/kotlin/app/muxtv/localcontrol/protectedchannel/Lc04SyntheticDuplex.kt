package app.muxtv.localcontrol.protectedchannel

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LC04-S2B: cryptographic *laboratory* for directional, strictly ordered AEAD
 * messages. NOT a socket, authenticated handshake or IPTV source channel.
 *
 * The only plaintext that this API can generate or accept is a 32-byte
 * synthetic random probe with a fixed domain prefix. No arbitrary application
 * payload, M3U/Xtream, credentials, source handles or pairing capabilities.
 *
 * Both independent AES-256-GCM keysets below exist solely in the in-process
 * test fixture. No production API currently provisions these session keys:
 * the existing one-shot TV receipt MUST NOT be interpreted as such a key.
 */
internal enum class Lc04DuplexRole { PHONE, TV }

internal class Lc04SyntheticDuplexEndpoint private constructor(
    private val role: Lc04DuplexRole,
    private val elapsedMillis: () -> Long,
    private val createdAt: Long,
    bindingDigest: ByteArray,
    outbound: Aead,
    inbound: Aead,
    private val random: SecureRandom,
) : AutoCloseable {
    private val binding = bindingDigest.clone()
    private var outboundAead: Aead? = outbound
    private var inboundAead: Aead? = inbound
    private var nextOut = 0L
    private var nextIn = 0L
    private var closed = false

    init {
        require(binding.size == BINDING_BYTES)
        require(createdAt >= 0 && createdAt <= Long.MAX_VALUE - TTL_MILLIS)
    }

    /**
     * Tink AEAD chooses its own random AES-GCM nonce. Header fields are
     * authenticated as AAD, including the role, exact binding and counter.
     * Counters are directional and do not survive close/expiry.
     */
    @Synchronized
    fun sendSyntheticProbe(): ByteArray? {
        if (!alive() || nextOut >= MAX_PROBES) return null
        val cipher = outboundAead ?: return null
        val next = nextOut + 1L
        val direction = if (role == Lc04DuplexRole.PHONE) PHONE_TO_TV else TV_TO_PHONE
        val plain = ByteArray(PROBE_BYTES).also(random::nextBytes)
        DOMAIN.copyInto(plain, 0)
        val header = header(direction, next, EXPECTED_CIPHERTEXT_BYTES)
        val aad = aad(header)
        return try {
            val sealed = cipher.encrypt(plain, aad)
            if (sealed.size != EXPECTED_CIPHERTEXT_BYTES) {
                close()
                null
            } else {
                nextOut = next
                header + sealed
            }
        } catch (_: GeneralSecurityException) {
            close()
            null
        } finally {
            plain.fill(0)
            header.fill(0)
            aad.fill(0)
        }
    }

    /**
     * Strictly sequential, not merely a nonce cache. Any malformed, replayed,
     * reflected, out-of-order, cross-session or invalid-tag packet revokes the
     * whole one-use lab session. This sacrifices availability, not authenticity.
     */
    @Synchronized
    fun receiveSyntheticProbe(wire: ByteArray): Boolean {
        if (!alive() || nextIn >= MAX_PROBES) return false
        if (wire.size != HEADER_BYTES + EXPECTED_CIPHERTEXT_BYTES) return reject()
        val header = wire.copyOfRange(0, HEADER_BYTES)
        val expectedDirection = if (role == Lc04DuplexRole.PHONE) TV_TO_PHONE else PHONE_TO_TV
        try {
            if (!matchesMagic(header) || header[4].toInt() != VERSION ||
                (header[5].toInt() and 0xff) != expectedDirection
            ) return reject()
            val incomingBinding = header.copyOfRange(6, 6 + BINDING_BYTES)
            val matched = try {
                MessageDigest.isEqual(binding, incomingBinding)
            } finally { incomingBinding.fill(0) }
            if (!matched) return reject()
            val count = ByteBuffer.wrap(header, 38, 8)
                .order(ByteOrder.BIG_ENDIAN).long
            if (count != nextIn + 1L || count > MAX_PROBES) return reject()
            val declaredSize = ((header[46].toInt() and 0xff) shl 8) or
                (header[47].toInt() and 0xff)
            if (declaredSize != EXPECTED_CIPHERTEXT_BYTES) return reject()
            val cipher = inboundAead ?: return reject()
            val sealed = wire.copyOfRange(HEADER_BYTES, wire.size)
            val aad = aad(header)
            val decrypted = try {
                cipher.decrypt(sealed, aad)
            } catch (_: GeneralSecurityException) {
                return reject()
            } finally {
                sealed.fill(0)
                aad.fill(0)
            }
            val valid = try {
                decrypted.size == PROBE_BYTES && MessageDigest.isEqual(
                    decrypted.copyOfRange(0, DOMAIN.size), DOMAIN,
                )
            } finally { decrypted.fill(0) }
            if (!valid) return reject()
            nextIn = count
            return true
        } finally {
            header.fill(0)
        }
    }

    private fun header(direction: Int, counter: Long, encryptedSize: Int): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(direction.toByte())
            .put(binding)
            .putLong(counter)
            .putShort(encryptedSize.toShort())
            .array()

    private fun aad(header: ByteArray): ByteArray = AAD_DOMAIN + header

    private fun matchesMagic(header: ByteArray): Boolean {
        var difference = 0
        for (i in MAGIC.indices) {
            difference = difference or (header[i].toInt() xor MAGIC[i].toInt())
        }
        return difference == 0
    }

    private fun alive(): Boolean {
        if (closed || outboundAead == null || inboundAead == null) return false
        val now = elapsedMillis()
        if (now < createdAt || now - createdAt >= TTL_MILLIS) {
            close()
            return false
        }
        return true
    }

    private fun reject(): Boolean {
        close()
        return false
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        nextOut = MAX_PROBES
        nextIn = MAX_PROBES
        binding.fill(0)
        outboundAead = null
        inboundAead = null
        // Tink owns its key material: JVM heap zeroization cannot be promised.
    }

    override fun toString(): String = "Lc04SyntheticDuplexEndpoint([REDACTED])"

    companion object {
        private val MAGIC = byteArrayOf(0x4d, 0x58, 0x45, 0x31) // MXE1
        private val DOMAIN = "LC04-SYNTHETIC".toByteArray(Charsets.US_ASCII)
        private val AAD_DOMAIN = "MuxTV-LC04-MXE1-SEALED-v1|".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val PHONE_TO_TV = 1
        private const val TV_TO_PHONE = 2
        private const val BINDING_BYTES = 32
        private const val HEADER_BYTES = 48
        private const val PROBE_BYTES = 32
        // Tink TINK output = 5-byte key prefix + 12-byte random nonce +
        // 32-byte synthetic probe + 16-byte authentication tag.
        // Keep Tink's authenticated/key-identified ciphertext format intact.
        private const val EXPECTED_CIPHERTEXT_BYTES = 65
        private const val MAX_PROBES = 64L
        private const val TTL_MILLIS = 120_000L

        /**
         * In-process synthetic fixture; deliberately not a TV/phone handshake.
         * A real authenticated two-way key exchange is a distinct future review.
         */
        internal fun newSyntheticPairForLab(
            now: () -> Long,
            random: SecureRandom = SecureRandom(),
        ): Pair<Lc04SyntheticDuplexEndpoint, Lc04SyntheticDuplexEndpoint> {
            val started = now()
            require(started >= 0 && started <= Long.MAX_VALUE - TTL_MILLIS)
            AeadConfig.register()
            fun newAead(): Aead = KeysetHandle.newBuilder()
                .addEntry(
                    KeysetHandle.generateEntryFromParametersName("AES256_GCM")
                        .withRandomId().makePrimary(),
                ).build()
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            val phoneToTv = newAead()
            val tvToPhone = newAead()
            val binding = ByteArray(BINDING_BYTES).also(random::nextBytes)
            try {
                return Lc04SyntheticDuplexEndpoint(
                    Lc04DuplexRole.PHONE, now, started,
                    binding, phoneToTv, tvToPhone, random,
                ) to Lc04SyntheticDuplexEndpoint(
                    Lc04DuplexRole.TV, now, started,
                    binding, tvToPhone, phoneToTv, random,
                )
            } finally {
                binding.fill(0)
            }
        }
    }
}

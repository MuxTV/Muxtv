package app.muxtv.localcontrol.sourceaead

import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommandCodec
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LC04-S2J3B. Real directional AES-256-GCM for the bounded M3U/Xtream
 * command vocabulary. This is a record engine, NOT a handshake or socket.
 *
 * There is intentionally NO production factory: neither a synthetic MXH1
 * success bit nor the existing spent MXR1 keys can authorize source data.
 * An independent, verified original-QR/approved-phone/TV-finished key-owner
 * transition is REQUIRED before this engine can be wired into Android.
 *
 * For review this module only exposes an INTERNAL, isolated host fixture
 * with fresh random dedicated app seed. An app cannot create this object or
 * extract keys. The source DTOs themselves are NEVER a pairing grant.
 */
internal class Lc04SourceAeadRecords private constructor(
    private val role: Role,
    phoneToTv: ByteArray,
    tvToPhone: ByteArray,
    bindingDigest: ByteArray,
    private val now: () -> Long,
    private val routeEligible: () -> Boolean,
) : Lc04SourceAeadPort {
    private enum class Role { PHONE, TV }

    private var outboundKey: ByteArray? =
        (if (role == Role.PHONE) phoneToTv else tvToPhone).clone()
    private var inboundKey: ByteArray? =
        (if (role == Role.PHONE) tvToPhone else phoneToTv).clone()
    private val binding = bindingDigest.clone()
    private val createdAt = now()
    private var lastClock = createdAt
    private var nextOutgoing = 1L
    private var nextIncoming = 1L
    private var responsePending = false
    private var closed = false

    init {
        require(phoneToTv.size == KEY_BYTES && tvToPhone.size == KEY_BYTES)
        require(binding.size == KEY_BYTES)
        require(createdAt >= 0 && createdAt <= Long.MAX_VALUE - TTL_MS)
    }

    /** The phone sends exactly one outstanding typed command at a time. */
    @Synchronized
    override fun sealRequest(command: Lc04SourceCommand): ByteArray? {
        if (role != Role.PHONE || !alive() || responsePending ||
            command.sequence != nextOutgoing || nextOutgoing > MAX_COMMANDS
        ) return fail()
        val plain = Lc04SourceCommandCodec.encodeRequest(command) ?: return fail()
        return try {
            val wire = seal(PHONE_TO_TV, nextOutgoing, plain) ?: return fail()
            nextOutgoing++
            responsePending = true
            wire
        } finally {
            plain.fill(0)
        }
    }

    /**
     * TV receives typed commands only AFTER AEAD authentication, fixed
     * direction, exact next sequence and original transcript binding.
     */
    @Synchronized
    override fun openRequest(untrusted: ByteArray): Lc04SourceCommand? {
        if (role != Role.TV || !alive() || responsePending ||
            nextIncoming > MAX_COMMANDS
        ) return failOpen()
        val plain = open(untrusted, PHONE_TO_TV, nextIncoming, false)
            ?: return failOpen()
        return try {
            val command = Lc04SourceCommandCodec.decodeRequest(plain)
                ?: return failOpen()
            if (command.sequence != nextIncoming) return failOpen()
            nextIncoming++
            responsePending = true
            command
        } finally {
            plain.fill(0)
        }
    }

    /** TV response carries ONLY the fixed source staging result code. */
    @Synchronized
    override fun sealReply(reply: Lc04SourceReply): ByteArray? {
        if (role != Role.TV || !alive() || !responsePending ||
            reply.sequence != nextOutgoing || nextOutgoing > MAX_COMMANDS
        ) return fail()
        val plain = Lc04SourceCommandCodec.encodeReply(reply) ?: return fail()
        return try {
            val wire = seal(TV_TO_PHONE, nextOutgoing, plain) ?: return fail()
            nextOutgoing++
            responsePending = false
            wire
        } finally {
            plain.fill(0)
        }
    }

    /** Reply must match the one pending command and authenticate TV direction. */
    @Synchronized
    override fun openReply(untrusted: ByteArray): Lc04SourceReply? {
        if (role != Role.PHONE || !alive() || !responsePending ||
            nextIncoming >= nextOutgoing || nextIncoming > MAX_COMMANDS
        ) return failOpen()
        val plain = open(untrusted, TV_TO_PHONE, nextIncoming, true)
            ?: return failOpen()
        return try {
            val reply = Lc04SourceCommandCodec.decodeReply(plain) ?: return failOpen()
            if (reply.sequence != nextIncoming) return failOpen()
            nextIncoming++
            responsePending = false
            reply
        } finally {
            plain.fill(0)
        }
    }

    private fun seal(direction: Int, sequence: Long, plain: ByteArray): ByteArray? {
        val key = outboundKey ?: return null
        val nonce = nonce(sequence)
        val header = header(direction, sequence, plain.size + GCM_TAG_BYTES, nonce)
        val aad = AAD_DOMAIN + header
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plain)
            if (ciphertext.size != plain.size + GCM_TAG_BYTES) null
            else header + ciphertext
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            nonce.fill(0)
            header.fill(0)
            aad.fill(0)
        }
    }

    private fun open(
        untrusted: ByteArray, direction: Int, sequence: Long, reply: Boolean,
    ): ByteArray? {
        val key = inboundKey ?: return null
        if (untrusted.size !in (HEADER_BYTES + MIN_CIPHERTEXT_BYTES)..
            (HEADER_BYTES + MAX_CIPHERTEXT_BYTES)
        ) return null
        val snapshot = untrusted.clone()
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.BIG_ENDIAN)
        val header = snapshot.copyOfRange(0, HEADER_BYTES)
        return try {
            var mismatch = 0
            for (magicByte in MAGIC) mismatch = mismatch or
                ((buffer.get().toInt() and 255) xor (magicByte.toInt() and 255))
            mismatch = mismatch or ((buffer.get().toInt() and 255) xor VERSION)
            mismatch = mismatch or ((buffer.get().toInt() and 255) xor direction)
            mismatch = mismatch or (buffer.short.toInt() and 0xffff)
            val wireBinding = ByteArray(KEY_BYTES)
            buffer.get(wireBinding)
            val bindingMatches = try {
                MessageDigest.isEqual(binding, wireBinding)
            } finally { wireBinding.fill(0) }
            val wireSequence = buffer.long
            val cipherSize = buffer.short.toInt() and 0xffff
            val wireNonce = ByteArray(NONCE_BYTES)
            buffer.get(wireNonce)
            val expectedNonce = nonce(sequence)
            val nonceMatches = try {
                MessageDigest.isEqual(wireNonce, expectedNonce)
            } finally {
                wireNonce.fill(0)
                expectedNonce.fill(0)
            }
            if (mismatch != 0 || !bindingMatches || !nonceMatches ||
                wireSequence != sequence || sequence !in 1..MAX_COMMANDS ||
                cipherSize != snapshot.size - HEADER_BYTES ||
                cipherSize !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES ||
                (reply && cipherSize != REPLY_CIPHERTEXT_BYTES)
            ) return null
            val encrypted = snapshot.copyOfRange(HEADER_BYTES, snapshot.size)
            val aad = AAD_DOMAIN + header
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce(sequence)))
                cipher.updateAAD(aad)
                cipher.doFinal(encrypted)
            } catch (_: GeneralSecurityException) {
                null
            } finally {
                encrypted.fill(0)
                aad.fill(0)
            }
        } finally {
            header.fill(0)
            snapshot.fill(0)
        }
    }

    private fun header(
        direction: Int, sequence: Long, ciphertextSize: Int, nonce: ByteArray,
    ): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        .put(MAGIC).put(VERSION.toByte()).put(direction.toByte()).putShort(0)
        .put(binding).putLong(sequence).putShort(ciphertextSize.toShort())
        .put(nonce).array()

    private fun nonce(sequence: Long): ByteArray =
        ByteBuffer.allocate(NONCE_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putLong(sequence).array()

    private fun alive(): Boolean {
        if (closed || outboundKey == null || inboundKey == null) return false
        // Android route/permission providers can throw during onStop or a
        // binder/network transition; exceptions must revoke traffic keys.
        val eligible = try { routeEligible() } catch (_: RuntimeException) { false }
        if (!eligible) { close(); return false }
        val current = try { now() } catch (_: RuntimeException) {
            close()
            return false
        }
        if (current < createdAt || current < lastClock ||
            current - createdAt >= TTL_MS
        ) { close(); return false }
        lastClock = current
        return true
    }

    private fun fail(): ByteArray? { close(); return null }
    private fun <T> failOpen(): T? { close(); return null }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        outboundKey?.fill(0)
        inboundKey?.fill(0)
        outboundKey = null
        inboundKey = null
        binding.fill(0)
        nextIncoming = MAX_COMMANDS + 1L
        nextOutgoing = MAX_COMMANDS + 1L
        responsePending = false
    }

    override fun toString(): String = "Lc04SourceAeadRecords([REDACTED])"

    companion object {
        private val MAGIC = byteArrayOf(0x4d, 0x58, 0x41, 0x31) // MXA1
        private val AAD_DOMAIN =
            "MuxTV-LC04-APPDATA-AEAD-v1\u0000".toByteArray(Charsets.US_ASCII)
        private val SALT_DOMAIN =
            "MuxTV-LC04-APPDATA-EXTRACT-v1\u0000".toByteArray(Charsets.US_ASCII)
        private val EXPAND_DOMAIN =
            "MuxTV-LC04-APPDATA-EXPAND-v1\u0000".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val PHONE_TO_TV = 1
        private const val TV_TO_PHONE = 2
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val HEADER_BYTES = 62
        private const val MIN_CIPHERTEXT_BYTES = 32 // 16-byte cancel or response + tag
        private const val MAX_CIPHERTEXT_BYTES =
            Lc04SourceCommandCodec.MAX_REQUEST_BYTES + GCM_TAG_BYTES
        private const val REPLY_CIPHERTEXT_BYTES = 32
        private const val MAX_COMMANDS = 16L
        private const val TTL_MS = 120_000L

        /**
         * Only the original QR/approved phone handshake OWNER may invoke this
         * after validating the exact signed offer, F4, F5, F6 and applicable
         * TCP EOF. This method creates NO SourceOnboarding principal/grant.
         * Do not call from an Activity, HTTP endpoint or synthetic proof bit.
         */
        internal fun fromAuthenticatedOriginalCandidate(
            dedicatedAppSeed: ByteArray,
            bindingDigest: ByteArray,
            phoneSide: Boolean,
            now: () -> Long,
            routeEligible: () -> Boolean,
        ): Lc04SourceAeadPort {
            require(dedicatedAppSeed.size == KEY_BYTES &&
                dedicatedAppSeed.any { it != 0.toByte() })
            require(bindingDigest.size == KEY_BYTES)
            val keys = deriveKeys(dedicatedAppSeed, bindingDigest)
            return try {
                Lc04SourceAeadRecords(
                    if (phoneSide) Role.PHONE else Role.TV,
                    keys.first, keys.second, bindingDigest, now, routeEligible,
                )
            } finally {
                keys.first.fill(0)
                keys.second.fill(0)
            }
        }

        /**
         * NOT a production credential/key exchange. Only the module's tests
         * can call this internal fixture. Future integration must receive a
         * dedicated, authenticated MXH1-bound app seed from the exact QR owner,
         * never the synthetic MXR1 traffic keys.
         */
        internal fun isolatedHostPairForTests(
            now: () -> Long,
            phoneRouteEligible: () -> Boolean = { true },
            tvRouteEligible: () -> Boolean = { true },
            random: SecureRandom = SecureRandom(),
        ): Pair<Lc04SourceAeadRecords, Lc04SourceAeadRecords> {
            val seed = ByteArray(KEY_BYTES).also(random::nextBytes)
            val binding = ByteArray(KEY_BYTES).also(random::nextBytes)
            require(seed.any { it != 0.toByte() } && binding.any { it != 0.toByte() })
            val keys = deriveKeys(seed, binding)
            try {
                return Lc04SourceAeadRecords(
                    Role.PHONE, keys.first, keys.second, binding, now, phoneRouteEligible,
                ) to Lc04SourceAeadRecords(
                    Role.TV, keys.first, keys.second, binding, now, tvRouteEligible,
                )
            } finally {
                seed.fill(0)
                binding.fill(0)
                keys.first.fill(0)
                keys.second.fill(0)
            }
        }

        /** Standard RFC 5869 SHA-256, with disjoint APPDATA domains. */
        private fun deriveKeys(seed: ByteArray, binding: ByteArray): Pair<ByteArray, ByteArray> {
            val salt = MessageDigest.getInstance("SHA-256").digest(SALT_DOMAIN + binding)
            val prk = hmac(salt, seed)
            return try {
                fun key(label: String): ByteArray {
                    val info = EXPAND_DOMAIN + label.toByteArray(Charsets.US_ASCII) + binding
                    return try { hmac(prk, info + byteArrayOf(1)) }
                    finally { info.fill(0) }
                }
                key("phone-to-tv|") to key("tv-to-phone|")
            } finally {
                salt.fill(0)
                prk.fill(0)
            }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}

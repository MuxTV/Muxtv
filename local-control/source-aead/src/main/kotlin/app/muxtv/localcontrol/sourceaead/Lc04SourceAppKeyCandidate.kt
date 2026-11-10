package app.muxtv.localcontrol.sourceaead

import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A typed, ephemeral record port, not a pairing grant or an IPC endpoint.
 * It can only process the bounded source-command vocabulary. Any access to
 * SourceOnboarding still requires a separate original-TV-owned principal and
 * explicit local TV approval; no key owner may mint that authority.
 */
interface Lc04SourceAeadPort : AutoCloseable {
    fun sealRequest(command: Lc04SourceCommand): ByteArray?
    fun openRequest(wire: ByteArray): Lc04SourceCommand?
    fun sealReply(reply: Lc04SourceReply): ByteArray?
    fun openReply(wire: ByteArray): Lc04SourceReply?
    override fun close()
}

/**
 * One-way original MXH1 key export: HKDF-SHA256 under a NEW app-specific
 * extract/expand domain, independent of the already-used MXR1 traffic keys.
 *
 * NOT a handshake verifier. This pending candidate MUST be created ONLY by
 * the original TV QR owner after Ed25519 + HPKE checks, or by the original
 * phone owner after confirming the authentic TV HMAC. The owner must verify
 * F5/F6 and TCP EOF before calling finishAfterAuthenticatedSixth().
 *
 * Both sides bind final app keys to the exact authenticated encrypted F6
 * frame (including the GCM tag), on top of the original QR/phone/offer/TV
 * nonce transcript digest. The TV prepares a candidate before writing F6;
 * the first authenticated app request will prove the phone received F6.
 */
class Lc04SourceAppKeyCandidate private constructor(
    originalHpkeSeed: ByteArray,
    transcriptDigest: ByteArray,
) : AutoCloseable {
    private var stageSecret: ByteArray?
    private val transcript = transcriptDigest.clone()
    private var closed = false

    init {
        require(originalHpkeSeed.size == 32 && originalHpkeSeed.any { it != 0.toByte() })
        require(transcript.size == 32)
        val salt = MessageDigest.getInstance("SHA-256").digest(EXTRACT_DOMAIN + transcript)
        val prk = hmac(salt, originalHpkeSeed)
        val info = EXPAND_DOMAIN + transcript + byteArrayOf(1)
        try {
            stageSecret = hmac(prk, info)
        } finally {
            salt.fill(0)
            prk.fill(0)
            info.fill(0)
        }
    }

    /**
     * The exact F6 input must already have been authenticated by the original
     * session owner. This method never authenticates a sixth frame on its own.
     * Invoking it twice, before owner proof, or after revocation is forbidden
     * by the original owner; any invalid input terminates this candidate.
     *
     * Returned traffic port stays owned by the original QR/phone session:
     * closing the original owner MUST also close this port.
     */
    @Synchronized
    fun finishAfterAuthenticatedSixth(
        exactAuthenticatedSixth: ByteArray,
        phoneSide: Boolean,
        now: () -> Long,
        routeEligible: () -> Boolean = { true },
    ): Lc04SourceAeadPort? {
        val stage = stageSecret ?: return null
        if (closed || exactAuthenticatedSixth.size != SIXTH_BYTES) {
            close()
            return null
        }
        // Domain-separated final binding. Never use the synthetic F5 or
        // TV_FINISHED alone: both peers must possess the exact encrypted F6.
        val finalDigest = MessageDigest.getInstance("SHA-256").digest(
            FINAL_DOMAIN + exactAuthenticatedSixth,
        )
        val binding = MessageDigest.getInstance("SHA-256").digest(
            BIND_DOMAIN + transcript + finalDigest,
        )
        val info = TRAFFIC_DOMAIN + binding + byteArrayOf(1)
        val appSeed = hmac(stage, info)
        return try {
            Lc04SourceAeadRecords.fromAuthenticatedOriginalCandidate(
                appSeed, binding, phoneSide, now, routeEligible,
            )
        } catch (_: RuntimeException) {
            null
        } finally {
            appSeed.fill(0)
            info.fill(0)
            binding.fill(0)
            finalDigest.fill(0)
            close()
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        stageSecret?.fill(0)
        stageSecret = null
        transcript.fill(0)
    }

    override fun toString(): String = "Lc04SourceAppKeyCandidate([REDACTED])"

    companion object {
        private const val SIXTH_BYTES = 104
        private val EXTRACT_DOMAIN =
            "MuxTV-LC04-SOURCE-EXPORT-EXTRACT-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
        private val EXPAND_DOMAIN =
            "MuxTV-LC04-SOURCE-EXPORT-EXPAND-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
        private val FINAL_DOMAIN =
            "MuxTV-LC04-SOURCE-F6-DIGEST-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
        private val BIND_DOMAIN =
            "MuxTV-LC04-SOURCE-TRANSCRIPT-BIND-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
        private val TRAFFIC_DOMAIN =
            "MuxTV-LC04-SOURCE-TRAFFIC-SEED-v1\u0000".toByteArray(StandardCharsets.US_ASCII)

        /**
         * Creating a candidate is NOT proof of an original verified session.
         * This API is a deterministic KDF, not a TV auth token or privilege.
         * It must never be called by HTTP, an Android Activity or a client-
         * supplied transcript; only real signed/approved MXH1 owners may call.
         */
        fun prepareFromOriginalHpkeSeed(
            originalHpkeSeed: ByteArray,
            authenticatedTranscriptDigest: ByteArray,
        ): Lc04SourceAppKeyCandidate =
            Lc04SourceAppKeyCandidate(
                originalHpkeSeed, authenticatedTranscriptDigest,
            )

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}

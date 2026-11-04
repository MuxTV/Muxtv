package app.muxtv.localcontrol.mxh1

import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticDerivedKeys
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticHandoffKeySchedule
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticHandoffTranscript
import app.muxtv.localcontrol.qr.TvQrPublicKey
import app.muxtv.localcontrol.wirelab.Lc04HandoffFrame
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import app.muxtv.localcontrol.wirelab.Lc04MxH1HpkeContext
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * One-shot JVM synthetic phone, pinned to the actual QR public HPKE key.
 * The phone can never approve itself on TV. The only successful response
 * requires an authenticated HMAC computed with the HPKE-delivered dedicated
 * seed and a fresh TV nonce. No app-data encryption or pairing grant.
 */
class Lc04MxH1PhoneLab(
    private val scannedTv: TvQrPublicKey,
    private val signingPhone: PhoneSigningIdentity,
    private val elapsedMillis: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private enum class Phase { NEW, WAIT_TV, VERIFIED, REVOKED }

    private var phase = Phase.NEW
    private var born: Long = -1L
    private var pendingSeed: ByteArray? = null
    private var offered: ByteArray? = null
    private var keys: Lc04SyntheticDerivedKeys? = null

    private fun live(): Boolean {
        val now = elapsedMillis()
        return born >= 0 && now >= born && now - born < TTL_MILLIS &&
            phase != Phase.REVOKED
    }

    /** One request, generated only after an actual phone-side user action. */
    @Synchronized
    fun createSignedSeedOffer(counter: Int = 1): ByteArray? {
        if (phase != Phase.NEW || counter !in 1..128) return reject()
        born = elapsedMillis()
        if (!live()) return reject()
        val seed = ByteArray(32).also(random::nextBytes)
        if (seed.all { it == 0.toByte() }) {
            seed.fill(0)
            return reject()
        }
        val contextFrame = Lc04HandoffFrame(
            Lc04HandoffKind.SIGNED_SEED_OFFER,
            fromHex(scannedTv.invitationId), fromHex(scannedTv.pairingId),
            fromHex(scannedTv.fingerprint), fromHex(signingPhone.fingerprint),
            ByteArray(32), counter,
            Lc04HandoffWireLab.signedOfferBody(ByteArray(17), ByteArray(69)),
        )
        val contextInfo = Lc04MxH1HpkeContext.forOffer(contextFrame)
        return try {
            HybridConfig.register()
            val encrypted = scannedTv.publicKeyset.getPrimitive(
                RegistryConfiguration.get(), HybridEncrypt::class.java,
            ).encrypt(seed, contextInfo)
            try {
                val signed = signingPhone.signMxH1SyntheticSeedOffer(
                    scannedTv, counter, encrypted,
                )
                offered = signed.clone()
                pendingSeed = seed.clone()
                phase = Phase.WAIT_TV
                signed
            } finally { encrypted.fill(0) }
        } catch (_: GeneralSecurityException) {
            reject()
        } catch (_: IllegalArgumentException) {
            reject()
        } catch (_: IllegalStateException) {
            reject()
        } finally {
            seed.fill(0)
            contextInfo.fill(0)
        }
    }

    /**
     * Fails closed on a forged TV-confirmation MAC, even when the MXH1
     * envelope, public QR fields and SHA-256 digest are all valid.
     */
    @Synchronized
    fun acceptTvConfirmation(confirmation: ByteArray): ByteArray? {
        if (phase != Phase.WAIT_TV || !live()) return reject()
        val offeredBytes = offered ?: return reject()
        val secret = pendingSeed ?: return reject()
        if (!Lc04HandoffWireLab.structurallyMatchesOffer(
                offeredBytes, confirmation, Lc04HandoffKind.TV_KEY_CONFIRM,
            )
        ) return reject()
        val frame = Lc04HandoffWireLab.decode(confirmation) ?: return reject()
        val source = Lc04HandoffWireLab.decode(offeredBytes) ?: return reject()
        val digest = Lc04HandoffWireLab.digestOfSignedOffer(offeredBytes) ?: return reject()
        val body = frame.payload()
        val tvNonce = body.copyOfRange(0, 32)
        val tvTag = body.copyOfRange(32, 64)
        return try {
            val transcript = Lc04SyntheticHandoffTranscript(
                source.invitation(), source.pairing(), source.tv(), source.phone(),
                digest, tvNonce,
            )
            val derived = Lc04SyntheticHandoffKeySchedule.derive(secret, transcript)
            if (!derived.verifySyntheticTvConfirmation(tvTag)) {
                derived.close()
                return reject()
            }
            val ourTag = derived.syntheticPhoneConfirmation()
            if (ourTag == null) {
                derived.close()
                return reject()
            }
            try {
                val reply = Lc04HandoffWireLab.encode(Lc04HandoffFrame(
                    Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    source.invitation(), source.pairing(), source.tv(), source.phone(),
                    digest, source.counter, ourTag,
                ))
                keys = derived
                pendingSeed?.fill(0)
                pendingSeed = null
                offered?.fill(0)
                offered = null
                phase = Phase.VERIFIED
                reply
            } finally { ourTag.fill(0) }
        } catch (_: IllegalArgumentException) {
            reject()
        } finally {
            digest.fill(0)
            body.fill(0)
            tvNonce.fill(0)
            tvTag.fill(0)
        }
    }

    @Synchronized
    fun hasAuthenticatedTvSyntheticProof(): Boolean {
        if (!live()) {
            close()
            return false
        }
        return phase == Phase.VERIFIED
    }

    private fun reject(): ByteArray? {
        close()
        return null
    }

    @Synchronized
    override fun close() {
        if (phase == Phase.REVOKED) return
        phase = Phase.REVOKED
        pendingSeed?.fill(0)
        pendingSeed = null
        offered?.fill(0)
        offered = null
        keys?.close()
        keys = null
        signingPhone.close()
    }

    override fun toString(): String = "Lc04MxH1PhoneLab([REDACTED])"

    companion object { private const val TTL_MILLIS = 120_000L }
}

private fun fromHex(value: String): ByteArray {
    require(value.length % 2 == 0 && value.all {
        it in '0'..'9' || it in 'a'..'f'
    })
    return ByteArray(value.length / 2) {
        ((value[2 * it].digitToInt(16) shl 4) or
            value[2 * it + 1].digitToInt(16)).toByte()
    }
}

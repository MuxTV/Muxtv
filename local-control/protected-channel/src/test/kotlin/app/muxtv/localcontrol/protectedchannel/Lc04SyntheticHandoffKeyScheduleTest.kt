package app.muxtv.localcontrol.protectedchannel

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentDenial
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.enrollment.TvPhoneEnrollment
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.wirelab.Lc04TypedTranscript
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.junit.Test

/** Test-only Tink HPKE and Ed25519 handoff, never an Android network adapter. */
class Lc04SyntheticHandoffKeyScheduleTest {
    private val random = SecureRandom()

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        (((value[it * 2].digitToInt(16) shl 4) or
            value[it * 2 + 1].digitToInt(16))).toByte()
    }

    private fun context(
        invite: ByteArray = ByteArray(16) { 1 },
        pairing: ByteArray = ByteArray(16) { 2 },
        tv: ByteArray = ByteArray(32) { 3 },
        phone: ByteArray = ByteArray(32) { 4 },
        signed: ByteArray = ByteArray(32) { 5 },
        nonce: ByteArray = ByteArray(32) { 6 },
    ) = Lc04SyntheticHandoffTranscript(invite, pairing, tv, phone, signed, nonce)

    @Test fun hkdfSha256MatchesRfc5869TestCaseOneWithoutTruncatingBlocks() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val prk = Lc04Rfc5869HkdfSha256.extract(salt, ikm)
        assertThat(prk.toHex()).isEqualTo(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
        )
        assertThat(Lc04Rfc5869HkdfSha256.expand(prk, info, 42).toHex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        prk.fill(0)
    }

    @Test fun sameIndependentSeedAndExactTranscriptYieldSameKeysAndMutualConfirmation() {
        val seed = ByteArray(32).also(random::nextBytes)
        val c = context()
        val a = Lc04SyntheticHandoffKeySchedule.derive(seed, c)
        val b = Lc04SyntheticHandoffKeySchedule.derive(seed, c)
        try {
            assertThat(a.syntheticDirectionDigest(true)).isEqualTo(b.syntheticDirectionDigest(true))
            assertThat(a.syntheticDirectionDigest(false)).isEqualTo(b.syntheticDirectionDigest(false))
            assertThat(a.syntheticDirectionDigest(true)).isNotEqualTo(a.syntheticDirectionDigest(false))
            val tvTag = checkNotNull(a.syntheticTvConfirmation())
            val phoneTag = checkNotNull(b.syntheticPhoneConfirmation())
            assertThat(b.verifySyntheticTvConfirmation(tvTag)).isTrue()
            assertThat(a.verifySyntheticPhoneConfirmation(phoneTag)).isTrue()
            assertThat(a.verifySyntheticPhoneConfirmation(tvTag)).isFalse()
            assertThat(b.verifySyntheticTvConfirmation(phoneTag)).isFalse()
            assertThat(tvTag).isNotEqualTo(phoneTag)
            tvTag.fill(0)
            phoneTag.fill(0)
        } finally { a.close(); b.close(); seed.fill(0) }
    }

    @Test fun exactQrIdsBothFingerprintsSignedDigestAndTvNonceAreIndependentBindings() {
        val seed = ByteArray(32).also(random::nextBytes)
        val base = Lc04SyntheticHandoffKeySchedule.derive(seed, context())
        try {
            val alternatives = listOf(
                context(invite = ByteArray(16) { 9 }),
                context(pairing = ByteArray(16) { 9 }),
                context(tv = ByteArray(32) { 9 }),
                context(phone = ByteArray(32) { 9 }),
                context(signed = ByteArray(32) { 9 }),
                context(nonce = ByteArray(32) { 9 }),
            )
            val tag = checkNotNull(base.syntheticTvConfirmation())
            for (changed in alternatives) {
                val other = Lc04SyntheticHandoffKeySchedule.derive(seed, changed)
                try {
                    assertThat(other.verifySyntheticTvConfirmation(tag)).isFalse()
                    assertThat(other.syntheticDirectionDigest(true))
                        .isNotEqualTo(base.syntheticDirectionDigest(true))
                    assertThat(other.syntheticDirectionDigest(false))
                        .isNotEqualTo(base.syntheticDirectionDigest(false))
                } finally { other.close() }
            }
            tag.fill(0)
        } finally { base.close(); seed.fill(0) }
    }

    @Test fun wrongDedicatedSeedAndReceiptKeyConfusionCannotConfirm() {
        val ownSeed = ByteArray(32).also(random::nextBytes)
        val differentSeed = ByteArray(32).also(random::nextBytes)
        val right = Lc04SyntheticHandoffKeySchedule.derive(ownSeed, context())
        val wrong = Lc04SyntheticHandoffKeySchedule.derive(differentSeed, context())
        try {
            assertThat(wrong.verifySyntheticTvConfirmation(
                checkNotNull(right.syntheticTvConfirmation()),
            )).isFalse()
            assertThat(right.syntheticDirectionDigest(true))
                .isNotEqualTo(wrong.syntheticDirectionDigest(true))
        } finally {
            ownSeed.fill(0); differentSeed.fill(0)
            right.close(); wrong.close()
        }
    }

    @Test fun invalidEntropyLengthAndTranscriptLengthsRejectBeforeAnyKey() {
        for (bad in listOf(ByteArray(0), ByteArray(31), ByteArray(33), ByteArray(32))) {
            assertThat(runCatching {
                Lc04SyntheticHandoffKeySchedule.derive(bad, context())
            }.isFailure).isTrue()
        }
        assertThat(runCatching { context(invite = ByteArray(15)) }.isFailure).isTrue()
        assertThat(runCatching { context(pairing = ByteArray(17)) }.isFailure).isTrue()
        assertThat(runCatching { context(tv = ByteArray(31)) }.isFailure).isTrue()
        assertThat(runCatching { context(phone = ByteArray(33)) }.isFailure).isTrue()
        assertThat(runCatching { context(signed = ByteArray(30)) }.isFailure).isTrue()
        assertThat(runCatching { context(nonce = ByteArray(20)) }.isFailure).isTrue()
        assertThat(runCatching { Lc04Rfc5869HkdfSha256.expand(ByteArray(32), byteArrayOf(), 8161) }
            .isFailure).isTrue()
    }

    @Test fun closeIsTerminalAndRejectsAllConfirmationsWithoutSerializingSecrets() {
        val seed = ByteArray(32).also(random::nextBytes)
        val derived = Lc04SyntheticHandoffKeySchedule.derive(seed, context())
        seed.fill(0)
        val tag = checkNotNull(derived.syntheticTvConfirmation())
        derived.close()
        derived.close()
        assertThat(derived.syntheticTvConfirmation()).isNull()
        assertThat(derived.syntheticPhoneConfirmation()).isNull()
        assertThat(derived.syntheticDirectionDigest(true)).isNull()
        assertThat(derived.verifySyntheticTvConfirmation(tag)).isFalse()
        assertThat(derived.toString()).contains("[REDACTED]")
        tag.fill(0)
    }

    /**
     * Test-only end-to-end composition: a real production canonical QR parser,
     * TV-local enrollment consent gate, Tink Ed25519 proof and Tink HPKE key
     * decryption. Unlike #425 this fixture is in one JVM and DOES NOT exercise
     * the Android phone/TV network, receipt authority or production data-plane.
     */
    @Test fun tvLocalApprovedEd25519SignedHpkeSeedEnablesMutualSyntheticKeyConfirmation() {
        HybridConfig.register()
        SignatureConfig.register()
        val receiverKeys = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.generateEntryFromParametersName(
                "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM",
            ).withRandomId().makePrimary())
            .build()
        val tvPublic = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            receiverKeys.publicKeysetHandle,
        )
        val invitation = "1".repeat(32)
        val pairing = "2".repeat(32)
        val qrString = "MXLC04:1:$invitation:$pairing:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(tvPublic)
        val qr = TvQrPublicKeyParser.parse(qrString)
        val phone = PhoneSigningIdentity.generate()
        val gate = TvPhoneEnrollment(
            qrString, elapsedMillis = { 10_000L }, tvReceiverIsAlive = { true },
        )
        val independentSeed = ByteArray(32).also(random::nextBytes)
        var tvRecovered: ByteArray? = null
        try {
            val publicOffer = Lc04PublicEnrollmentWire.phoneOffer(qr, phone)
            val tvChallenge = checkNotNull(gate.beginPublicOffer(publicOffer))
            val decoded = checkNotNull(Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                qr, phone, Lc04PublicEnrollmentWire.tvChallenge(tvChallenge),
            ))
            val signedEnrollment = phone.prove(qr, decoded)
            val proofWire = Lc04PublicEnrollmentWire.phoneProof(qr, phone, signedEnrollment)
            val premature = gate.verifyPublicProof(proofWire)
            assertThat(premature).isInstanceOf(PhoneEnrollmentResult.Denied::class.java)
            assertThat((premature as PhoneEnrollmentResult.Denied).reason)
                .isEqualTo(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
            assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
            val authenticated = gate.verifyPublicProof(proofWire)
            assertThat(authenticated).isInstanceOf(PhoneEnrollmentResult.Verified::class.java)
            val tvApproved = (authenticated as PhoneEnrollmentResult.Verified).identity

            // This is a fresh KDF SEED, unrelated to the 32-byte one-shot
            // TV-receipt key or its 16-byte proof challenge.
            val hpkeContext = ("MuxTV-LC04-SYNTHETIC-SEPARATE-HPKE-HANDOFF-v1|" +
                qr.invitationId + "|" + qr.pairingId + "|" + qr.fingerprint + "|" +
                phone.fingerprint).toByteArray(Charsets.US_ASCII)
            val encrypted = qr.publicKeyset.getPrimitive(
                RegistryConfiguration.get(), HybridEncrypt::class.java,
            ).encrypt(independentSeed, hpkeContext)
            val signedHandoffWire = phone.signProtectedProbe(qr, 1, encrypted)
            val signedFrame = checkNotNull(Lc04WireLab.decode(signedHandoffWire))
            val signedBody = checkNotNull(Lc04TypedWire.decodeProbeFrame(signedFrame))
            assertThat(signedFrame.tvInvitationId()).isEqualTo(hex(tvApproved.tvInvitationId))
            assertThat(signedFrame.tvPairingId()).isEqualTo(hex(tvApproved.tvPairingId))
            assertThat(signedFrame.tvHpkeFingerprint()).isEqualTo(hex(tvApproved.tvFingerprint))
            assertThat(signedFrame.phoneKeyFingerprint()).isEqualTo(hex(tvApproved.fingerprint))
            val verifier = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                tvApproved.publicKeysetBytes(),
            ).getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
            val statement = Lc04TypedTranscript.probeSigningStatement(signedFrame, signedBody)
            verifier.verify(signedBody.signature(), statement)
            val alteredStatement = statement.clone()
            alteredStatement[alteredStatement.lastIndex] =
                (alteredStatement.last().toInt() xor 1).toByte()
            assertThat(runCatching {
                verifier.verify(signedBody.signature(), alteredStatement)
            }.isFailure).isTrue()
            alteredStatement.fill(0)

            val tvDecryptor = receiverKeys.getPrimitive(
                RegistryConfiguration.get(), HybridDecrypt::class.java,
            )
            val alteredHpkeContext = hpkeContext.clone()
            alteredHpkeContext[alteredHpkeContext.lastIndex] =
                (alteredHpkeContext.last().toInt() xor 1).toByte()
            assertThat(runCatching {
                tvDecryptor.decrypt(signedBody.ciphertext(), alteredHpkeContext)
            }.isFailure).isTrue()
            alteredHpkeContext.fill(0)
            val alteredCiphertext = signedBody.ciphertext()
            alteredCiphertext[alteredCiphertext.lastIndex] =
                (alteredCiphertext.last().toInt() xor 1).toByte()
            assertThat(runCatching {
                tvDecryptor.decrypt(alteredCiphertext, hpkeContext)
            }.isFailure).isTrue()
            alteredCiphertext.fill(0)
            tvRecovered = tvDecryptor.decrypt(signedBody.ciphertext(), hpkeContext)
            assertThat(tvRecovered).isEqualTo(independentSeed)
            val transcript = Lc04SyntheticHandoffTranscript(
                hex(qr.invitationId), hex(qr.pairingId), hex(qr.fingerprint),
                hex(phone.fingerprint),
                Lc04TypedTranscript.originalSignedRequestDigest(signedFrame),
                ByteArray(32).also(random::nextBytes),
            )
            val fromPhone = Lc04SyntheticHandoffKeySchedule.derive(independentSeed, transcript)
            val fromTv = Lc04SyntheticHandoffKeySchedule.derive(
                checkNotNull(tvRecovered), transcript,
            )
            try {
                val signedTvConfirm = checkNotNull(fromTv.syntheticTvConfirmation())
                val signedPhoneConfirm = checkNotNull(fromPhone.syntheticPhoneConfirmation())
                assertThat(fromPhone.verifySyntheticTvConfirmation(signedTvConfirm)).isTrue()
                assertThat(fromTv.verifySyntheticPhoneConfirmation(signedPhoneConfirm)).isTrue()
                assertThat(fromTv.verifySyntheticPhoneConfirmation(signedTvConfirm)).isFalse()
                assertThat(fromPhone.syntheticDirectionDigest(true))
                    .isEqualTo(fromTv.syntheticDirectionDigest(true))
                assertThat(fromPhone.syntheticDirectionDigest(false))
                    .isEqualTo(fromTv.syntheticDirectionDigest(false))
                signedTvConfirm.fill(0)
                signedPhoneConfirm.fill(0)
            } finally {
                fromPhone.close(); fromTv.close()
                statement.fill(0); hpkeContext.fill(0); encrypted.fill(0)
                publicOffer.fill(0); proofWire.fill(0); signedHandoffWire.fill(0)
            }
        } finally {
            tvRecovered?.fill(0)
            independentSeed.fill(0)
            gate.close(); phone.close()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}

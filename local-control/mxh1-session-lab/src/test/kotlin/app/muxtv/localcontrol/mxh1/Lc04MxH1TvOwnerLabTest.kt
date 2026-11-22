package app.muxtv.localcontrol.mxh1

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentDenial
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.wirelab.Lc04HandoffFrame
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * No mocks for signature/HPKE/key confirmation. TV-local consent is checked
 * through the actual enrollment gate; transport is deliberately absent.
 */
class Lc04MxH1TvOwnerLabTest {
    private class Fixture(
        val time: AtomicLong = AtomicLong(10_000L),
        val tv: Lc04MxH1TvOwnerLab = Lc04MxH1TvOwnerLab.create(
            nowElapsedMillis = time.get(), elapsedMillis = time::get,
        ),
        val phoneIdentity: PhoneSigningIdentity = PhoneSigningIdentity.generate(),
    ) : AutoCloseable {
        val qr = TvQrPublicKeyParser.parse(tv.publicQr)
        val phone = Lc04MxH1PhoneLab(qr, phoneIdentity, time::get)

        fun enrollWithTvLocalApproval() {
            val gate = checkNotNull(tv.tvConsentGate())
            val publicOffer = Lc04PublicEnrollmentWire.phoneOffer(qr, phoneIdentity)
            val challenge = checkNotNull(gate.beginPublicOffer(publicOffer))
            val decoded = checkNotNull(Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                qr, phoneIdentity, Lc04PublicEnrollmentWire.tvChallenge(challenge),
            ))
            val signed = phoneIdentity.prove(qr, decoded)
            val wire = Lc04PublicEnrollmentWire.phoneProof(qr, phoneIdentity, signed)
            try {
                val premature = gate.verifyPublicProof(wire) as PhoneEnrollmentResult.Denied
                assertThat(premature.reason).isEqualTo(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
                assertThat(gate.approveOnTv(phoneIdentity.fingerprint)).isTrue()
                val verified = gate.verifyPublicProof(wire) as PhoneEnrollmentResult.Verified
                assertThat(tv.activateApprovedPhone(verified.identity)).isTrue()
            } finally { publicOffer.fill(0); wire.fill(0) }
        }

        fun offer(): ByteArray = checkNotNull(phone.createSignedSeedOffer())
        fun tvReply(offer: ByteArray): ByteArray = checkNotNull(tv.acceptSignedSeedOffer(offer))
        fun phoneReply(tvConfirm: ByteArray): ByteArray = checkNotNull(phone.acceptTvConfirmation(tvConfirm))

        override fun close() {
            phone.close()
            tv.close()
            phoneIdentity.close()
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fx = Fixture()
        try { test(fx) } finally { fx.close() }
    }

    @Test fun exactQrTvLocalConsentAndMutualHmacConfirmOnlyOneSyntheticSession() =
        withFixture { f ->
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            f.enrollWithTvLocalApproval()
            val signedOffer = f.offer()
            val tvConfirm = f.tvReply(signedOffer)
            val phoneConfirm = f.phoneReply(tvConfirm)
            assertThat(f.tv.acceptPhoneKeyConfirmation(phoneConfirm)).isTrue()
            assertThat(f.tv.hasMutualSyntheticProof()).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                signedOffer, tvConfirm, Lc04HandoffKind.TV_KEY_CONFIRM,
            )).isTrue()
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                signedOffer, phoneConfirm, Lc04HandoffKind.PHONE_KEY_CONFIRM,
            )).isTrue()
            assertThat(f.phone.createSignedSeedOffer()).isNull()
            signedOffer.fill(0); tvConfirm.fill(0); phoneConfirm.fill(0)
        }

    @Test fun tvRejectsSeedOfferBeforeItsOwnTvLocalApprovedEd25519Identity() =
        withFixture { f ->
            val offered = f.offer()
            assertThat(f.tv.acceptSignedSeedOffer(offered)).isNull()
            assertThat(f.tv.tvConsentGate()).isNull()
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
        }

    @Test fun forgedTvConfirmationWithCorrectQrIdsAndDigestFailsActualKeyMac() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            val valid = f.tvReply(offer)
            val forged = valid.clone()
            forged[forged.lastIndex] = (forged.last().toInt() xor 1).toByte()
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offer, forged, Lc04HandoffKind.TV_KEY_CONFIRM,
            )).isTrue()
            assertThat(f.phone.acceptTvConfirmation(forged)).isNull()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            assertThat(f.phone.acceptTvConfirmation(valid)).isNull()
        }

    @Test fun forgedPhoneConfirmationWithCorrectOfferDigestFailsActualKeyMac() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            val tvTag = f.tvReply(offer)
            val valid = f.phoneReply(tvTag)
            val forged = valid.clone()
            forged[forged.lastIndex] = (forged.last().toInt() xor 1).toByte()
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offer, forged, Lc04HandoffKind.PHONE_KEY_CONFIRM,
            )).isTrue()
            assertThat(f.tv.acceptPhoneKeyConfirmation(forged)).isFalse()
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
            assertThat(f.tv.acceptPhoneKeyConfirmation(valid)).isFalse()
        }

    @Test fun changedSignedSeedOfferIdentitiesCiphertextAndSignatureFailClosed() {
        for (index in listOf(6, 22, 38, 70, 138 + 4, 135, 102, 200)) {
            withFixture { f ->
                f.enrollWithTvLocalApproval()
                val offered = f.offer()
                val changed = offered.clone()
                changed[index] = (changed[index].toInt() xor 1).toByte()
                assertThat(f.tv.acceptSignedSeedOffer(changed)).isNull()
                assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
                assertThat(f.tv.acceptSignedSeedOffer(offered)).isNull()
            }
        }
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offered = f.offer()
            offered[offered.lastIndex] = (offered.last().toInt() xor 1).toByte()
            assertThat(f.tv.acceptSignedSeedOffer(offered)).isNull()
        }
    }

    @Test fun wrongHpkeInfoEvenWithValidSignedOfferCannotDecryptOnTv() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            HybridConfig.register()
            val secret = ByteArray(32) { 0x5a.toByte() }
            val wrongDomain = "MuxTV-LC04-MXW1-PROBE-DIFFERENT-CONTEXT".toByteArray()
            val encrypted = f.qr.publicKeyset.getPrimitive(
                RegistryConfiguration.get(), HybridEncrypt::class.java,
            ).encrypt(secret, wrongDomain)
            val wire = f.phoneIdentity.signMxH1SyntheticSeedOffer(f.qr, 1, encrypted)
            assertThat(f.tv.acceptSignedSeedOffer(wire)).isNull()
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
            secret.fill(0); encrypted.fill(0); wrongDomain.fill(0); wire.fill(0)
        }

    @Test fun wrongPhoneIdentityCannotUseAnotherApprovedPhoneFingerprint() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val intruder = PhoneSigningIdentity.generate()
            try {
                val bad = Lc04MxH1PhoneLab(f.qr, intruder, f.time::get)
                val attack = checkNotNull(bad.createSignedSeedOffer())
                assertThat(f.tv.acceptSignedSeedOffer(attack)).isNull()
                assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
                bad.close()
            } finally { intruder.close() }
        }

    @Test fun replayOfSignedOfferBeforePhoneConfirmationRevokesTvOwner() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val original = f.offer()
            val reply = f.tvReply(original)
            assertThat(f.tv.acceptSignedSeedOffer(original)).isNull()
            val validPhone = f.phoneReply(reply)
            assertThat(f.tv.acceptPhoneKeyConfirmation(validPhone)).isFalse()
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
        }

    @Test fun replayOfPhoneConfirmationAfterSuccessfulHandshakeRevokes() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val confirmation = f.phoneReply(f.tvReply(f.offer()))
            assertThat(f.tv.acceptPhoneKeyConfirmation(confirmation)).isTrue()
            assertThat(f.tv.acceptPhoneKeyConfirmation(confirmation)).isFalse()
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
        }

    @Test fun wrongCounterAndForeignConfirmationCannotAdvanceEitherState() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            val tvConfirm = f.tvReply(offer)
            val forgedCounter = tvConfirm.clone()
            forgedCounter[135] = (forgedCounter[135].toInt() xor 1).toByte()
            assertThat(f.phone.acceptTvConfirmation(forgedCounter)).isNull()
            assertThat(f.phone.acceptTvConfirmation(tvConfirm)).isNull()
        }

    @Test fun expiryClockRollbackAndExplicitStopErasePendingState() {
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            f.time.addAndGet(120_000)
            assertThat(f.tv.acceptSignedSeedOffer(offer)).isNull()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
        }
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            f.time.decrementAndGet()
            assertThat(f.tv.acceptSignedSeedOffer(offer)).isNull()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
        }
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            f.tv.close()
            f.phone.close()
            assertThat(f.tv.acceptSignedSeedOffer(offer)).isNull()
            assertThat(f.phone.createSignedSeedOffer()).isNull()
            assertThat(f.tv.hasMutualSyntheticProof()).isFalse()
        }
    }

    @Test fun malformedAndMxw1MessagesHaveNoAuthPath() =
        withFixture { f ->
            f.enrollWithTvLocalApproval()
            val offer = f.offer()
            assertThat(f.tv.acceptSignedSeedOffer(offer.copyOf(offer.size - 1))).isNull()
            assertThat(f.tv.acceptSignedSeedOffer(offer)).isNull()
        }

    @Test fun logsNeverExposePrivateReceiverSeedOrAnyDerivedKey() =
        withFixture { f ->
            assertThat(f.tv.toString()).contains("[REDACTED]")
            assertThat(f.phone.toString()).contains("[REDACTED]")
            assertThat(f.tv.publicQr).startsWith("MXLC04:1:")
        }
}

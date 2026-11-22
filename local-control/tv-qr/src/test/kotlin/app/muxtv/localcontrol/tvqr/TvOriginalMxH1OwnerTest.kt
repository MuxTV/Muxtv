package app.muxtv.localcontrol.tvqr

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import org.junit.Test

/**
 * Actual existing TvHpkeQrSession + its TV-owned Ed25519 approval gate.
 * No second TV QR/key owner, no Android TCP, source API or bearer authority.
 */
class TvOriginalMxH1OwnerTest {
    private class Fixture : AutoCloseable {
        var now = 10_000L
        val tv = TvHpkeQrSession.create(now)
        val shownQr = checkNotNull(tv.presentation(now)).qrValue
        val scannedQr = TvQrPublicKeyParser.parse(shownQr)
        val signingPhone = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(scannedQr, signingPhone, { now })
        val gate = checkNotNull(tv.phoneEnrollmentGate { now })

        fun approveRealPhone() {
            val challenge = checkNotNull(gate.begin(signingPhone.publicKeysetBytes()))
            assertThat(tv.phoneEnrollmentGate { now }).isSameInstanceAs(gate)
            val signed = signingPhone.prove(scannedQr, challenge)
            assertThat(gate.approveOnTv(signingPhone.fingerprint)).isTrue()
            val approved = gate.verify(signed) as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(approved.identity, now)).isTrue()
        }

        fun offer() = checkNotNull(phone.createSignedSeedOffer())
        fun tvConfirm(offer: ByteArray) =
            checkNotNull(tv.acceptSyntheticMxH1SeedOffer(offer, now))
        fun phoneConfirm(confirmation: ByteArray) =
            checkNotNull(phone.acceptTvConfirmation(confirmation))

        override fun close() {
            phone.close()
            signingPhone.close()
            tv.close()
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    @Test fun realExistingQrOwnerAcceptsTvApprovedPhoneAndCompletesBothHmacProofs() =
        withFixture { f ->
            f.approveRealPhone()
            val originalQr = checkNotNull(f.tv.presentation(f.now)).qrValue
            assertThat(originalQr).isEqualTo(f.shownQr)
            val offer = f.offer()
            val tvConfirm = f.tvConfirm(offer)
            val phoneConfirm = f.phoneConfirm(tvConfirm)
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offer, tvConfirm, Lc04HandoffKind.TV_KEY_CONFIRM,
            )).isTrue()
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offer, phoneConfirm, Lc04HandoffKind.PHONE_KEY_CONFIRM,
            )).isTrue()
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(
                phoneConfirm, f.now,
            )).isTrue()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.now)).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
            assertThat(checkNotNull(f.tv.presentation(f.now)).qrValue)
                .isEqualTo(originalQr)
            offer.fill(0); tvConfirm.fill(0); phoneConfirm.fill(0)
        }

    @Test fun authenticatedFourthTvFinishedIsRequiredBeforePhoneReportsNetworkProof() =
        withFixture { f ->
            f.approveRealPhone()
            val offer = f.offer()
            val tvConfirm = f.tvConfirm(offer)
            val phoneConfirm = checkNotNull(
                f.phone.acceptTvConfirmationAwaitingFinished(tvConfirm),
            )
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            val final = checkNotNull(
                f.tv.finishSyntheticMxH1PhoneConfirmation(phoneConfirm, f.now),
            )
            assertThat(Lc04HandoffWireLab.decode(final)?.kind)
                .isEqualTo(Lc04HandoffKind.TV_FINISHED)
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offer, final, Lc04HandoffKind.TV_FINISHED,
            )).isTrue()
            assertThat(f.phone.acceptTvFinished(final)).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.now)).isTrue()
            // A duplicate terminal proof revokes the phone's state.
            assertThat(f.phone.acceptTvFinished(final)).isFalse()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            offer.fill(0); tvConfirm.fill(0); phoneConfirm.fill(0); final.fill(0)
        }

    @Test fun forgedFinishedWithValidPublicOfferBindingNeverAuthenticatesPhone() =
        withFixture { f ->
            f.approveRealPhone()
            val offered = f.offer()
            val phoneTag = checkNotNull(
                f.phone.acceptTvConfirmationAwaitingFinished(f.tvConfirm(offered)),
            )
            val actual = checkNotNull(
                f.tv.finishSyntheticMxH1PhoneConfirmation(phoneTag, f.now),
            )
            val forged = actual.clone().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offered, forged, Lc04HandoffKind.TV_FINISHED,
            )).isTrue()
            assertThat(f.phone.acceptTvFinished(forged)).isFalse()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            assertThat(f.phone.acceptTvFinished(actual)).isFalse()
        }

    @Test fun missingFinishedOnLifecycleCloseRevokesPendingPhoneAndTvKeys() =
        withFixture { f ->
            f.approveRealPhone()
            val phoneTag = checkNotNull(
                f.phone.acceptTvConfirmationAwaitingFinished(f.tvConfirm(f.offer())),
            )
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            f.tv.close()
            f.phone.close()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            assertThat(f.tv.finishSyntheticMxH1PhoneConfirmation(phoneTag, f.now)).isNull()
            assertThat(f.tv.presentation(f.now)).isNull()
        }

    @Test fun mxh1OfferBeforeExactTvConsentRevokesOriginalQrAndGate() =
        withFixture { f ->
            val offer = f.offer()
            assertThat(f.tv.acceptSyntheticMxH1SeedOffer(offer, f.now)).isNull()
            assertThat(f.tv.presentation(f.now)).isNull()
            assertThat(f.gate.pendingFingerprintForTv()).isNull()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.now)).isFalse()
            assertThat(f.tv.phoneEnrollmentGate { f.now }).isNull()
        }

    @Test fun forgedTvMacWithCorrectSignedOfferDigestCannotBeAcceptedByPhone() =
        withFixture { f ->
            f.approveRealPhone()
            val offer = f.offer()
            val authentic = f.tvConfirm(offer)
            val forged = authentic.clone().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                offer, forged, Lc04HandoffKind.TV_KEY_CONFIRM,
            )).isTrue()
            assertThat(f.phone.acceptTvConfirmation(forged)).isNull()
            assertThat(f.phone.acceptTvConfirmation(authentic)).isNull()
        }

    @Test fun forgedPhoneMacAndReplayRevokeOriginalQrKeyOwner() =
        withFixture { f ->
            f.approveRealPhone()
            val offer = f.offer()
            val phoneProof = f.phoneConfirm(f.tvConfirm(offer))
            val forged = phoneProof.clone().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(forged, f.now))
                .isFalse()
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(phoneProof, f.now))
                .isFalse()
            assertThat(f.tv.presentation(f.now)).isNull()
        }

    @Test fun exactOfferReplayDuringOutstandingConfirmationRevokesOriginalOwner() =
        withFixture { f ->
            f.approveRealPhone()
            val offer = f.offer()
            val tvReply = f.tvConfirm(offer)
            assertThat(f.tv.acceptSyntheticMxH1SeedOffer(offer, f.now)).isNull()
            val phoneProof = f.phoneConfirm(tvReply)
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(phoneProof, f.now))
                .isFalse()
            assertThat(f.tv.presentation(f.now)).isNull()
        }

    @Test fun duplicatePhoneConfirmationAfterSuccessClosesPreviouslyVerifiedSession() =
        withFixture { f ->
            f.approveRealPhone()
            val phoneProof = f.phoneConfirm(f.tvConfirm(f.offer()))
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(phoneProof, f.now)).isTrue()
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(phoneProof, f.now)).isFalse()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.now)).isFalse()
            assertThat(f.tv.presentation(f.now)).isNull()
        }

    @Test fun tamperedSignedOfferHeaderCiphertextAndSignatureRevokeSameOwner() {
        for (index in listOf(6, 22, 38, 70, 135, 145, 200)) {
            withFixture { f ->
                f.approveRealPhone()
                val offer = f.offer()
                val altered = offer.clone().also {
                    it[index] = (it[index].toInt() xor 1).toByte()
                }
                assertThat(f.tv.acceptSyntheticMxH1SeedOffer(altered, f.now)).isNull()
                assertThat(f.tv.acceptSyntheticMxH1SeedOffer(offer, f.now)).isNull()
                assertThat(f.tv.presentation(f.now)).isNull()
            }
        }
        withFixture { f ->
            f.approveRealPhone()
            val altered = f.offer().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertThat(f.tv.acceptSyntheticMxH1SeedOffer(altered, f.now)).isNull()
        }
    }

    @Test fun domainSeparatedHpkeSeedUnderWrongContextCannotDecrypt() =
        withFixture { f ->
            f.approveRealPhone()
            HybridConfig.register()
            val secret = ByteArray(32) { 0x31.toByte() }
            val wrongInfo = "MuxTV-LC04-MXW1-OLD-PROBE-CONTEXT".toByteArray()
            val ciphertext = f.scannedQr.publicKeyset.getPrimitive(
                RegistryConfiguration.get(), HybridEncrypt::class.java,
            ).encrypt(secret, wrongInfo)
            val signed = f.signingPhone.signMxH1SyntheticSeedOffer(
                f.scannedQr, 1, ciphertext,
            )
            assertThat(f.tv.acceptSyntheticMxH1SeedOffer(signed, f.now)).isNull()
            assertThat(f.tv.presentation(f.now)).isNull()
            secret.fill(0); wrongInfo.fill(0); ciphertext.fill(0); signed.fill(0)
        }

    @Test fun oldMxw1ProbeCannotCoexistAfterMxH1HasStarted() =
        withFixture { f ->
            f.approveRealPhone()
            val mxh1 = f.offer()
            f.tvConfirm(mxh1)
            val legacySignedProbe = f.signingPhone.signProtectedProbe(
                f.scannedQr, 2, ByteArray(100),
            )
            assertThat(f.tv.acceptProtectedProbe(legacySignedProbe, f.now)).isNull()
            assertThat(f.tv.presentation(f.now)).isNotNull()
        }

    @Test fun originalActivityStopCloseAndExpiryEraseMxH1State() {
        withFixture { f ->
            f.approveRealPhone()
            val phoneProof = f.phoneConfirm(f.tvConfirm(f.offer()))
            f.tv.close() // TvPairingController.revokePublicKeyQrPreview / onStop
            assertThat(f.tv.acceptSyntheticMxH1PhoneConfirmation(phoneProof, f.now))
                .isFalse()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.now)).isFalse()
            assertThat(f.tv.presentation(f.now)).isNull()
        }
        withFixture { f ->
            f.approveRealPhone()
            val offer = f.offer()
            f.now += TvHpkeQrSession.TTL_MILLIS
            assertThat(f.tv.acceptSyntheticMxH1SeedOffer(offer, f.now)).isNull()
            assertThat(f.tv.presentation(f.now)).isNull()
        }
        withFixture { f ->
            f.approveRealPhone()
            val offer = f.offer()
            f.now -= 1
            assertThat(f.tv.acceptSyntheticMxH1SeedOffer(offer, f.now)).isNull()
            assertThat(f.tv.presentation(f.now)).isNull()
        }
    }

    @Test fun noSecretsInOwnerDiagnosticAndNoNewQrIdentity() =
        withFixture { f ->
            assertThat(f.tv.toString()).contains("[EPHEMERAL_KEY_REDACTED]")
            assertThat(f.tv.toString()).doesNotContain(f.shownQr)
            assertThat(f.tv.presentation(f.now)?.qrValue).isEqualTo(f.shownQr)
        }
}

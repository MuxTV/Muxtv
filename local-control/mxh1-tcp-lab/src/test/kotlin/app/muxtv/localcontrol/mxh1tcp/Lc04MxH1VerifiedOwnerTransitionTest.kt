package app.muxtv.localcontrol.mxh1tcp

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * S2J2A: actual TV-owned QR, TV-local-approved Ed25519 signer and real HPKE
 * four-frame authenticated MXH1 proof -> opaque fixed-synthetic AEAD port.
 *
 * In-process ONLY; no installed app, production TCP, source or bearer APIs.
 */
class Lc04MxH1VerifiedOwnerTransitionTest {
    private class Fixture : AutoCloseable {
        val clock = AtomicLong(10_000L)
        val tv = TvHpkeQrSession.create(clock.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(clock.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock::get)
        val gate = checkNotNull(tv.phoneEnrollmentGate(clock::get))

        fun approveActualTvLocalSigner() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, clock.get())).isTrue()
        }

        fun confirmation(): ByteArray {
            approveActualTvLocalSigner()
            val offer = checkNotNull(phone.createSignedSeedOffer())
            val tvConfirm = checkNotNull(tv.acceptSyntheticMxH1SeedOffer(offer, clock.get()))
            return checkNotNull(phone.acceptTvConfirmationAwaitingFinished(tvConfirm))
        }

        fun finishForLab(): ByteArray {
            val phoneConfirm = confirmation()
            return checkNotNull(tv.finishSyntheticMxH1ForProbeLab(phoneConfirm, clock::get))
        }

        fun fullyVerifyPhoneAndProveToTv() {
            val finished = finishForLab()
            assertThat(tv.sealMxH1SyntheticProbeForLab(clock.get())).isNull()
            assertThat(phone.acceptTvFinishedForProbeLab(finished, clock::get)).isTrue()
            val first = checkNotNull(phone.sealMxH1SyntheticProbeForLab())
            assertThat(first.size).isEqualTo(104)
            assertThat(tv.acceptMxH1SyntheticProbeForLab(first, clock.get())).isTrue()
        }

        override fun close() {
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { action(fixture) } finally { fixture.close() }
    }

    @Test fun tvCannotSendRecordsBeforePhoneProvesReceptionOfFinished() =
        withFixture { f ->
            f.fullyVerifyPhoneAndProveToTv()
            val reply = checkNotNull(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get()))
            assertThat(reply.size).isEqualTo(104)
            assertThat(f.phone.acceptMxH1SyntheticProbeForLab(reply)).isTrue()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.clock.get())).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
        }

    @Test fun forgedTvFinishedCannotGrantPhoneARecordOwner() =
        withFixture { f ->
            val authentic = f.finishForLab()
            assertThat(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get())).isNull()
            val forged = authentic.clone().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            assertThat(f.phone.acceptTvFinishedForProbeLab(forged, f.clock::get)).isFalse()
            assertThat(f.phone.sealMxH1SyntheticProbeForLab()).isNull()
            assertThat(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get())).isNull()
        }

    @Test fun defaultInstalledHandshakeStillClosesAllKeysAndOffersNoRecord() =
        withFixture { f ->
            val confirmation = f.confirmation()
            val finished = checkNotNull(
                f.tv.finishSyntheticMxH1PhoneConfirmation(confirmation, f.clock.get()),
            )
            assertThat(f.phone.acceptTvFinished(finished)).isTrue()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.clock.get())).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
            assertThat(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get())).isNull()
            assertThat(f.phone.sealMxH1SyntheticProbeForLab()).isNull()
        }

    @Test fun forgedFirstPostFinishedRecordRevokesOriginalTvQr() =
        withFixture { f ->
            val finished = f.finishForLab()
            assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.clock::get)).isTrue()
            val record = checkNotNull(f.phone.sealMxH1SyntheticProbeForLab())
            record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
            assertThat(f.tv.acceptMxH1SyntheticProbeForLab(record, f.clock.get())).isFalse()
            assertThat(f.tv.presentation(f.clock.get())).isNull()
            assertThat(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get())).isNull()
        }

    @Test fun replayOfCorrectPostFinishedRecordRevokesOwner() =
        withFixture { f ->
            val finished = f.finishForLab()
            assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.clock::get)).isTrue()
            val first = checkNotNull(f.phone.sealMxH1SyntheticProbeForLab())
            assertThat(f.tv.acceptMxH1SyntheticProbeForLab(first, f.clock.get())).isTrue()
            assertThat(f.tv.acceptMxH1SyntheticProbeForLab(first, f.clock.get())).isFalse()
            assertThat(f.tv.presentation(f.clock.get())).isNull()
        }

    @Test fun swappedTvRecordReflectionFailsClosed() =
        withFixture { f ->
            f.fullyVerifyPhoneAndProveToTv()
            val reply = checkNotNull(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get()))
            assertThat(f.tv.acceptMxH1SyntheticProbeForLab(reply, f.clock.get())).isFalse()
            assertThat(f.tv.presentation(f.clock.get())).isNull()
        }

    @Test fun tvStopBetweenFinishedAndPhoneProofRevokesAndCannotResume() =
        withFixture { f ->
            val finished = f.finishForLab()
            f.tv.close()
            assertThat(f.tv.presentation(f.clock.get())).isNull()
            assertThat(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get())).isNull()
            assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.clock::get)).isTrue()
            assertThat(f.phone.sealMxH1SyntheticProbeForLab()).isNotNull()
            // The original TV owner is gone; no authenticated response can be produced.
            assertThat(f.tv.acceptMxH1SyntheticProbeForLab(ByteArray(104), f.clock.get())).isFalse()
        }

    @Test fun ownerExpiryAfterAuthenticatedFinishedDeniesAllRecords() =
        withFixture { f ->
            f.fullyVerifyPhoneAndProveToTv()
            f.clock.addAndGet(TvHpkeQrSession.TTL_MILLIS)
            assertThat(f.tv.sealMxH1SyntheticProbeForLab(f.clock.get())).isNull()
            assertThat(f.phone.sealMxH1SyntheticProbeForLab()).isNull()
        }

    @Test fun secondPhoneTransitionWithoutNewVerifiedSessionFails() =
        withFixture { f ->
            val finished = f.finishForLab()
            assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.clock::get)).isTrue()
            assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.clock::get)).isFalse()
            assertThat(f.phone.sealMxH1SyntheticProbeForLab()).isNull()
        }

    @Test fun invalidPhoneRecordLengthAlsoRevokesOriginalQr() =
        withFixture { f ->
            val finished = f.finishForLab()
            assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.clock::get)).isTrue()
            assertThat(f.tv.acceptMxH1SyntheticProbeForLab(ByteArray(105), f.clock.get()))
                .isFalse()
            assertThat(f.tv.presentation(f.clock.get())).isNull()
        }
}

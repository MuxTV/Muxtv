package app.muxtv.localcontrol.phoneflow

import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.nativetransport.Lc04TvFrameDispatcher
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Untrusted serialized packet relay; TV and phone use real Tink primitives.
 * Only the TV-local gate can grant Ed25519 enrollment. No sockets or Android
 * privileges are introduced in this slice; the TV socket is tested in #420/#421.
 */
class Lc04PhoneProofFlowTest {
    private class Fixture {
        var now = 1_000L
        val clock: () -> Long = { now }
        val tv = TvHpkeQrSession.create(now)
        val scanned = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now)).qrValue)
        val phone = PhoneSigningIdentity.generate()
        val tvFrames = Lc04TvFrameDispatcher(tv, clock)

        fun flow(
            relay: Lc04PhoneFrameExchange = Lc04PhoneFrameExchange(tvFrames::handle),
        ): Lc04PhoneProofFlow = Lc04PhoneProofFlow(scanned, phone, relay, clock)

        fun approveOnActualTv(): Boolean {
            val gate = checkNotNull(tv.phoneEnrollmentGate(clock))
            return gate.approveOnTv(checkNotNull(gate.pendingFingerprintForTv()))
        }
    }

    @Test fun realTvConsentAndOriginalAuthenticatedReceiptAreRequired() {
        val f = Fixture()
        f.flow().use { phone ->
            val review = checkNotNull(phone.offer())
            assertThat(review.tvFingerprint).isEqualTo(f.scanned.fingerprint)
            assertThat(review.phoneFingerprint).isEqualTo(f.phone.fingerprint)
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            assertThat(f.approveOnActualTv()).isTrue()
            assertThat(phone.finishAfterTvAction()).isTrue()
            assertThat(phone.hasAuthenticatedReceipt()).isTrue()
            assertThat(phone.finishAfterTvAction()).isFalse()
        }
    }

    @Test fun publicQrAndChallengeWithoutTvDpadApprovalAreNeverEnough() {
        val f = Fixture()
        f.flow().use { phone ->
            assertThat(phone.offer()).isNotNull()
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            assertThat(phone.finishAfterTvAction()).isFalse()
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            assertThat(phone.offer()).isNull()
        }
    }

    @Test fun fakeAuthenticatedResponseIsRejectedEvenAfterTvApproval() {
        val f = Fixture()
        val forged = Lc04PhoneFrameExchange { wire ->
            when (Lc04WireLab.decode(wire)?.kind) {
                Lc04WireKind.SIGNED_CHANNEL_PROBE -> byteArrayOf(0x7F)
                else -> f.tvFrames.handle(wire)
            }
        }
        f.flow(forged).use { phone ->
            assertThat(phone.offer()).isNotNull()
            assertThat(f.approveOnActualTv()).isTrue()
            assertThat(phone.finishAfterTvAction()).isFalse()
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
        }
    }

    @Test fun silentDroppedPhoneProofCannotBeMistakenForEnrollmentSuccess() {
        val f = Fixture()
        val adversary = Lc04PhoneFrameExchange { wire ->
            if (Lc04WireLab.decode(wire)?.kind == Lc04WireKind.PHONE_PROOF) null
            else f.tvFrames.handle(wire)
        }
        f.flow(adversary).use { phone ->
            assertThat(phone.offer()).isNotNull()
            assertThat(f.approveOnActualTv()).isTrue()
            assertThat(phone.finishAfterTvAction()).isFalse()
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
        }
    }

    @Test fun mismatchedTvChallengeCannotBecomeAReviewOrSignerAuthority() {
        val f = Fixture()
        val wrongTv = TvHpkeQrSession.create(f.now)
        try {
            val wrong = Lc04TvFrameDispatcher(wrongTv, f.clock)
            f.flow(Lc04PhoneFrameExchange(wrong::handle)).use { phone ->
                assertThat(phone.offer()).isNull()
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            }
        } finally {
            wrongTv.close()
        }
    }

    @Test fun expiredQrNeverYieldsAuthenticatedReceipt() {
        val f = Fixture()
        f.flow().use { phone ->
            assertThat(phone.offer()).isNotNull()
            assertThat(f.approveOnActualTv()).isTrue()
            f.now += TvHpkeQrSession.TTL_MILLIS + 1
            assertThat(phone.finishAfterTvAction()).isFalse()
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
        }
    }

    @Test fun closeRacingUntrustedRelayCannotResurrectPhoneVerification() {
        val f = Fixture()
        lateinit var phone: Lc04PhoneProofFlow
        val adversary = Lc04PhoneFrameExchange { wire ->
            val answer = f.tvFrames.handle(wire)
            phone.close()
            answer
        }
        phone = f.flow(adversary)
        phone.use {
            assertThat(phone.offer()).isNull()
            assertThat(phone.finishAfterTvAction()).isFalse()
            assertThat(phone.hasAuthenticatedReceipt()).isFalse()
        }
    }

    @Test fun transportErrorDuringOfferFailsClosedAndNoRetryCanReusePhoneKey() {
        val f = Fixture()
        val failed = Lc04PhoneFrameExchange { throw java.io.IOException("untrusted peer") }
        f.flow(failed).use { phone ->
            assertThat(phone.offer()).isNull()
            assertThat(phone.offer()).isNull()
            assertThat(phone.finishAfterTvAction()).isFalse()
        }
    }

    @Test fun identityAndFlowDiagnosticsNeverContainKeyOrTvFingerprint() {
        val f = Fixture()
        f.flow().use { phone ->
            assertThat(phone.toString()).doesNotContain(f.scanned.fingerprint)
            assertThat(phone.toString()).doesNotContain(f.phone.fingerprint)
            val review = checkNotNull(phone.offer())
            assertThat(review.toString()).doesNotContain(f.scanned.fingerprint)
            assertThat(review.toString()).doesNotContain(f.phone.fingerprint)
        }
    }
}

package app.muxtv.localcontrol.tvqr

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * S2J3C: fresh application keys from the EXACT existing TV-owned QR,
 * local Ed25519 approval and original HPKE secret, not host fixture keys.
 * These tests simulate F5/F6 but cannot attest a kernel socket's TCP EOF.
 * The Android adapter must perform that last check before claiming ports.
 */
class TvOriginalMxH1SourceKeyHandoffTest {
    private class Fixture : AutoCloseable {
        var now = 12_000L
        var route = true
        val tv = TvHpkeQrSession.create(now)
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now)).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, { now })
        val gate = checkNotNull(tv.phoneEnrollmentGate { now })

        fun approve() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, now)).isTrue()
        }

        fun prepareFirstFour(): ByteArray {
            val offer = checkNotNull(phone.createSignedSeedOffer())
            val tvConfirmation = checkNotNull(
                tv.acceptSyntheticMxH1SeedOffer(offer, now),
            )
            val phoneConfirmation = checkNotNull(
                phone.acceptTvConfirmationAwaitingFinished(tvConfirmation),
            )
            val finished = checkNotNull(
                tv.finishSyntheticMxH1ForProbeLab(phoneConfirmation) { now },
            )
            try {
                assertThat(phone.acceptTvFinishedForProbeLab(finished) { now }).isTrue()
            } finally {
                offer.fill(0)
                tvConfirmation.fill(0)
                phoneConfirmation.fill(0)
                finished.fill(0)
            }
            return checkNotNull(phone.sealMxH1SyntheticProbeForLab())
        }

        fun finishAuthenticatedFifthAndSixth(fifth: ByteArray): ByteArray {
            assertThat(tv.acceptMxH1SyntheticProbeForLab(fifth, now)).isTrue()
            return checkNotNull(tv.sealMxH1SyntheticProbeForLab(now))
        }

        fun tvPort(): Lc04SourceAeadPort? =
            tv.claimSourceRecordsAfterSixthWritten(
                clock = { now }, routeEligible = { route },
            )

        fun phonePort(): Lc04SourceAeadPort? =
            phone.claimSourceRecordsAfterVerifiedTvEof(
                clock = { now }, routeEligible = { route },
            )

        override fun close() {
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    @Test fun noAppKeyPortBeforeOriginalTvApprovalOrBeforeFifthAndSixth() =
        fixture { f ->
            assertThat(f.tvPort()).isNull()
            assertThat(f.phonePort()).isNull()
            f.approve()
            val fifth = f.prepareFirstFour()
            assertThat(f.tvPort()).isNull()
            assertThat(f.phonePort()).isNull()
            val sixth = f.finishAuthenticatedFifthAndSixth(fifth)
            assertThat(f.tvPort()).isNotNull()
            // Phone has not authenticated the actual sealed sixth yet.
            assertThat(f.phonePort()).isNull()
            fifth.fill(0)
            sixth.fill(0)
        }

    @Test fun originalTvAndPhoneDerivedSourceKeysEncryptRealXtreamAndM3uFields() =
        fixture { f ->
            f.approve()
            val fifth = f.prepareFirstFour()
            val sixth = f.finishAuthenticatedFifthAndSixth(fifth)
            assertThat(f.phone.acceptMxH1SyntheticProbeForLab(sixth)).isTrue()
            val tvRecords = checkNotNull(f.tvPort())
            val phoneRecords = checkNotNull(f.phonePort())
            assertThat(f.tvPort()).isNull()
            assertThat(f.phonePort()).isNull()

            val xtream = checkNotNull(phoneRecords.sealRequest(
                Lc04SourceCommand.PrepareXtream(
                    1, "https://iptv.invalid", "username-PRIVATE", "password-PRIVATE",
                ),
            ))
            assertThat(String(xtream, Charsets.ISO_8859_1)).doesNotContain("password-PRIVATE")
            val parsed = tvRecords.openRequest(xtream)
                as Lc04SourceCommand.PrepareXtream
            assertThat(parsed.username).isEqualTo("username-PRIVATE")
            assertThat(parsed.password).isEqualTo("password-PRIVATE")
            assertThat(parsed.toString()).doesNotContain("PRIVATE")
            val reply = checkNotNull(tvRecords.sealReply(
                Lc04SourceReply(1, Lc04SourceReplyCode.PREPARED),
            ))
            assertThat(phoneRecords.openReply(reply)?.code)
                .isEqualTo(Lc04SourceReplyCode.PREPARED)

            val second = checkNotNull(phoneRecords.sealRequest(
                Lc04SourceCommand.PrepareM3u(2, "https://m3u.invalid/?auth=secret-PRIVATE"),
            ))
            val parsedM3u = tvRecords.openRequest(second) as Lc04SourceCommand.PrepareM3u
            assertThat(parsedM3u.locator).contains("secret-PRIVATE")
            val response = checkNotNull(tvRecords.sealReply(
                Lc04SourceReply(2, Lc04SourceReplyCode.ALREADY_PREPARED),
            ))
            assertThat(phoneRecords.openReply(response)?.code)
                .isEqualTo(Lc04SourceReplyCode.ALREADY_PREPARED)

            assertThat(tvRecords.toString()).doesNotContain("PRIVATE")
            assertThat(phoneRecords.toString()).doesNotContain("PRIVATE")
            fifth.fill(0); sixth.fill(0)
            xtream.fill(0); reply.fill(0); second.fill(0); response.fill(0)
        }

    @Test fun originalTvOnStopAndPhoneOnStopRevokeExportedKeys() =
        fixture { f ->
            f.approve()
            val fifth = f.prepareFirstFour()
            val sixth = f.finishAuthenticatedFifthAndSixth(fifth)
            assertThat(f.phone.acceptMxH1SyntheticProbeForLab(sixth)).isTrue()
            val tvPort = checkNotNull(f.tvPort())
            val phonePort = checkNotNull(f.phonePort())
            f.tv.close()
            assertThat(tvPort.openRequest(byteArrayOf(1))).isNull()
            f.phone.close()
            assertThat(phonePort.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
            fifth.fill(0); sixth.fill(0)
        }

    @Test fun verifiedSixthWithOneByteForgeryCannotReleasePhoneAppPort() =
        fixture { f ->
            f.approve()
            val fifth = f.prepareFirstFour()
            val sixth = f.finishAuthenticatedFifthAndSixth(fifth)
            val forged = sixth.clone().also {
                it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
            }
            assertThat(f.phone.acceptMxH1SyntheticProbeForLab(forged)).isFalse()
            assertThat(f.phonePort()).isNull()
            assertThat(f.phone.acceptMxH1SyntheticProbeForLab(sixth)).isFalse()
            fifth.fill(0); sixth.fill(0); forged.fill(0)
        }

    @Test fun wrongOriginalPhoneOrTvSessionCannotDecryptAnotherSourceAppKey() {
        val a = Fixture()
        val b = Fixture()
        try {
            a.approve()
            b.approve()
            fun finish(f: Fixture): Lc04SourceAeadPort {
                val fifth = f.prepareFirstFour()
                val sixth = f.finishAuthenticatedFifthAndSixth(fifth)
                try {
                    assertThat(f.phone.acceptMxH1SyntheticProbeForLab(sixth)).isTrue()
                    return checkNotNull(f.tvPort())
                } finally { fifth.fill(0); sixth.fill(0) }
            }
            val tvA = finish(a)
            val tvB = finish(b)
            val pA = checkNotNull(a.phonePort())
            val requestA = checkNotNull(pA.sealRequest(Lc04SourceCommand.Cancel(1)))
            assertThat(tvB.openRequest(requestA)).isNull()
            assertThat(tvA.openRequest(requestA)).isNotNull()
        } finally {
            a.close()
            b.close()
        }
    }

    @Test fun routeLossOrExpiredQrClosesApplicationKeyAfterHandoff() =
        fixture { f ->
            f.approve()
            val fifth = f.prepareFirstFour()
            val sixth = f.finishAuthenticatedFifthAndSixth(fifth)
            assertThat(f.phone.acceptMxH1SyntheticProbeForLab(sixth)).isTrue()
            val tvPort = checkNotNull(f.tvPort())
            val phonePort = checkNotNull(f.phonePort())
            f.route = false
            assertThat(phonePort.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
            f.now += TvHpkeQrSession.TTL_MILLIS
            assertThat(tvPort.openRequest(ByteArray(80))).isNull()
            fifth.fill(0); sixth.fill(0)
        }
}

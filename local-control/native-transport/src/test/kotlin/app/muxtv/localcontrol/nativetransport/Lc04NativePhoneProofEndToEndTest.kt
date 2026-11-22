package app.muxtv.localcontrol.nativetransport

import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.phoneflow.Lc04PhoneFrameExchange
import app.muxtv.localcontrol.phoneflow.Lc04PhoneProofFlow
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/**
 * LC04-S3-E4B: the real, bounded native TCP byte protocol together with the
 * accepted phone-side proof state machine and TV-local approval gate.
 *
 * Loopback is a JVM-only test escape hatch; production constructors still
 * require a canonical numeric private IPv4 address. No Android permissions,
 * cloud, physical device or provider credentials are involved.
 */
class Lc04NativePhoneProofEndToEndTest {
    @Volatile private var now = 10_000L
    private val clock: () -> Long = { now }

    private inner class Fixture : AutoCloseable {
        val tv = TvHpkeQrSession.create(now)
        val scanned = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now)).qrValue)
        val identity = PhoneSigningIdentity.generate()
        val server = Lc04TvTcpServer.startLoopbackForTests(tv, clock)
        val tcp = Lc04TcpPhoneExchange.loopbackForTests(server.port)

        fun flow(
            relay: Lc04PhoneFrameExchange = Lc04PhoneFrameExchange(tcp::exchange),
        ): Lc04PhoneProofFlow = Lc04PhoneProofFlow(scanned, identity, relay, clock)

        fun approveOnTv(): Boolean {
            val gate = checkNotNull(tv.phoneEnrollmentGate(clock))
            assertThat(gate.pendingFingerprintForTv()).isEqualTo(identity.fingerprint)
            return gate.approveOnTv(identity.fingerprint)
        }

        override fun close() {
            server.close()
            identity.close()
            tv.close()
        }
    }

    @Test fun fullRealTcpEnrollmentRequiresTvLocalApprovalAndAuthenticOneShotReceipt() {
        Fixture().use { f ->
            f.flow().use { phone ->
                val review = checkNotNull(phone.offer())
                assertThat(review.tvFingerprint).isEqualTo(f.scanned.fingerprint)
                assertThat(review.phoneFingerprint).isEqualTo(f.identity.fingerprint)
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
                assertThat(f.approveOnTv()).isTrue()
                assertThat(phone.finishAfterTvAction()).isTrue()
                assertThat(phone.hasAuthenticatedReceipt()).isTrue()
                assertThat(phone.finishAfterTvAction()).isFalse()
            }
        }
    }

    @Test fun realTcpChallengeWithoutTrustedTvDpadConsentIsDenied() {
        Fixture().use { f ->
            f.flow().use { phone ->
                assertThat(phone.offer()).isNotNull()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            }
        }
    }

    @Test fun proofDroppedByNetworkAdversaryDoesNotActivateReceiver() {
        Fixture().use { f ->
            val intercepted = AtomicInteger()
            val network = Lc04PhoneFrameExchange { wire ->
                if (Lc04WireLab.decode(wire)?.kind == Lc04WireKind.PHONE_PROOF) {
                    intercepted.incrementAndGet()
                    null // Untrusted peer may drop but cannot manufacture TV consent.
                } else f.tcp.exchange(wire)
            }
            f.flow(network).use { phone ->
                assertThat(phone.offer()).isNotNull()
                assertThat(f.approveOnTv()).isTrue()
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
                assertThat(intercepted.get()).isEqualTo(1)
            }
        }
    }

    @Test fun onPathMutationOfActualTvChallengeCannotBecomePhoneReview() {
        Fixture().use { f ->
            val adversary = Lc04PhoneFrameExchange { wire ->
                f.tcp.exchange(wire)?.also { response ->
                    if (Lc04WireLab.decode(wire)?.kind == Lc04WireKind.PHONE_PUBLIC_KEY) {
                        // Corrupt the exact original TV challenge *on the wire*.
                        response[30] = (response[30].toInt() xor 1).toByte()
                    }
                }
            }
            f.flow(adversary).use { phone ->
                assertThat(phone.offer()).isNull()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
                assertThat(phone.finishAfterTvAction()).isFalse()
            }
        }
    }

    @Test fun replayedOrMutatedSealedTvReceiptNeverQualifiesAsAuthenticatedTv() {
        Fixture().use { f ->
            var tampered = 0
            val adversary = Lc04PhoneFrameExchange { wire ->
                f.tcp.exchange(wire)?.also { reply ->
                    if (Lc04WireLab.decode(wire)?.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE) {
                        reply[reply.lastIndex] = (reply.last().toInt() xor 1).toByte()
                        tampered++
                    }
                }
            }
            f.flow(adversary).use { phone ->
                assertThat(phone.offer()).isNotNull()
                assertThat(f.approveOnTv()).isTrue()
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
                assertThat(tampered).isEqualTo(1)
            }
        }
    }

    @Test fun revokingQrAndNativeListenerAfterOfferPreventsProtectedReceipt() {
        Fixture().use { f ->
            f.flow().use { phone ->
                assertThat(phone.offer()).isNotNull()
                assertThat(f.approveOnTv()).isTrue()
                f.server.close() // TV Activity stop, LAN loss or default route change.
                assertThat(f.tv.presentation(now)).isNull()
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            }
        }
    }

    @Test fun qrExpiryBetweenTcpOfferAndEd25519ProofFailsClosed() {
        Fixture().use { f ->
            f.flow().use { phone ->
                assertThat(phone.offer()).isNotNull()
                assertThat(f.approveOnTv()).isTrue()
                now += TvHpkeQrSession.TTL_MILLIS
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            }
        }
    }

    @Test fun attackerChosenWrongTvSocketCannotAnswerScannedQrIdentity() {
        Fixture().use { f ->
            val wrong = TvHpkeQrSession.create(now)
            val wrongListener = Lc04TvTcpServer.startLoopbackForTests(wrong, clock)
            try {
                val wrongClient = Lc04TcpPhoneExchange.loopbackForTests(wrongListener.port)
                f.flow(Lc04PhoneFrameExchange(wrongClient::exchange)).use { phone ->
                    assertThat(phone.offer()).isNull()
                    assertThat(phone.finishAfterTvAction()).isFalse()
                    assertThat(phone.hasAuthenticatedReceipt()).isFalse()
                }
            } finally {
                wrongListener.close()
                wrong.close()
            }
        }
    }

    @Test fun closingPhoneDuringRealTcpReceiptCannotResurrectVerifiedState() {
        Fixture().use { f ->
            lateinit var phone: Lc04PhoneProofFlow
            val network = Lc04PhoneFrameExchange { wire ->
                f.tcp.exchange(wire).also {
                    if (Lc04WireLab.decode(wire)?.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE) {
                        // Simulate phone Activity.onStop while a real valid
                        // authenticated reply is in flight.
                        phone.close()
                    }
                }
            }
            phone = f.flow(network)
            phone.use {
                assertThat(phone.offer()).isNotNull()
                assertThat(f.approveOnTv()).isTrue()
                assertThat(phone.finishAfterTvAction()).isFalse()
                assertThat(phone.hasAuthenticatedReceipt()).isFalse()
            }
        }
    }
}

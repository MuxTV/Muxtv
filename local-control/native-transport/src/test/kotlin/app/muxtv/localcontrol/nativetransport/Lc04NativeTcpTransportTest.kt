package app.muxtv.localcontrol.nativetransport

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneProtectedProbeFactory
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04ReceiptBody
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Test

/** Real loopback TCP/byte streams on GitHub-hosted JVM only; no physical devices. */
class Lc04NativeTcpTransportTest {
    @Volatile private var now = 1_000L
    private val elapsed: () -> Long = { now }

    private class Fixture(
        val tv: TvHpkeQrSession,
        val phone: PhoneSigningIdentity,
        val server: Lc04TvTcpServer,
        val client: Lc04TcpPhoneExchange,
        val scanned: app.muxtv.localcontrol.qr.TvQrPublicKey,
        val clock: () -> Long,
    ) : AutoCloseable {
        fun pairWithTrustedTvAction() {
            val offer = Lc04PublicEnrollmentWire.phoneOffer(scanned, phone)
            val challengeWire = checkNotNull(client.exchange(offer))
            val decoded = checkNotNull(Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                scanned, phone, challengeWire,
            ))
            val proofWire = Lc04PublicEnrollmentWire.phoneProof(
                scanned, phone, phone.prove(scanned, decoded),
            )
            // An unsolicited proof before the TV-local button cannot unlock
            // the private receiver, and a network reply is never an approval.
            assertThat(client.exchange(proofWire)).isNull()
            assertThat(tv.acceptProtectedProbe(
                PhoneProtectedProbeFactory(scanned, phone, clock).use {
                    it.create().use { pending -> pending.requestBytes() }
                }, clock(),
            )).isNull()
            val gate = tv.phoneEnrollmentGate(clock)!!
            assertThat(gate.pendingFingerprintForTv()).isEqualTo(phone.fingerprint)
            assertThat(gate.approveOnTv(phone.fingerprint)).isTrue() // TV-only action
            assertThat(client.exchange(proofWire)).isNull()
        }

        override fun close() {
            server.close()
            phone.close()
            tv.close()
        }
    }

    private fun fixture(): Fixture {
        val tv = TvHpkeQrSession.create(now)
        val qr = TvQrPublicKeyParser.parse(tv.presentation(now)!!.qrValue)
        val phone = PhoneSigningIdentity.generate()
        val server = Lc04TvTcpServer.startLoopbackForTests(tv, elapsed)
        return Fixture(tv, phone, server,
            Lc04TcpPhoneExchange.loopbackForTests(server.port), qr, elapsed)
    }

    @Test fun actualTcpPublicEnrollmentAndAuthenticatedReplyRequireTrustedTvApproval() {
        fixture().use { f ->
            f.pairWithTrustedTvAction()
            PhoneProtectedProbeFactory(f.scanned, f.phone, elapsed).use { sender ->
                sender.create().use { pending ->
                    val signedBytes = pending.requestBytes()
                    val encrypted = checkNotNull(f.client.exchange(signedBytes))
                    assertThat(pending.verifyReply(encrypted)).isTrue()
                    assertThat(pending.verifyReply(encrypted)).isFalse()
                    assertThat(f.client.exchange(signedBytes)).isNull() // replay
                }
            }
        }
    }

    @Test fun forgedPhoneWithOnlyPublicTvQrCannotImpersonateEnrolledKeyOnTcp() {
        fixture().use { f ->
            f.pairWithTrustedTvAction()
            PhoneSigningIdentity.generate().use { intruder ->
                PhoneProtectedProbeFactory(f.scanned, intruder, elapsed).use { sender ->
                    sender.create().use { pending ->
                        assertThat(f.client.exchange(pending.requestBytes())).isNull()
                    }
                }
            }
            PhoneProtectedProbeFactory(f.scanned, f.phone, elapsed).use { sender ->
                sender.create().use { pending ->
                    assertThat(pending.verifyReply(checkNotNull(f.client.exchange(
                        pending.requestBytes(),
                    )))).isTrue()
                }
            }
        }
    }

    @Test fun hostileByteMutationAndTruncatedTcpFramesDoNotBypassEnrollment() {
        fixture().use { f ->
            val clean = Lc04PublicEnrollmentWire.phoneOffer(f.scanned, f.phone)
            val tampered = clean.clone().also { it[38] = (it[38].toInt() xor 1).toByte() }
            assertThat(f.client.exchange(tampered)).isNull()
            assertThat(f.tv.phoneEnrollmentGate(elapsed)!!.pendingFingerprintForTv()).isNull()
            // Raw malicious peers are deliberately separate from the phone client;
            // truncated input or declared >4200-byte body MUST be dropped.
            for ((announced, body) in listOf(
                (Lc04WireLab.MAX_FRAME_BYTES + 1) to byteArrayOf(),
                clean.size to clean.copyOf(clean.size - 1),
            )) {
                Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), f.server.port).use { s ->
                    s.soTimeout = 1_500
                    val output = DataOutputStream(s.getOutputStream())
                    output.writeInt(announced)
                    output.write(body)
                    output.flush()
                    s.shutdownOutput()
                    val rejected = runCatching {
                        DataInputStream(s.getInputStream()).readInt()
                    }.getOrNull()
                    assertThat(rejected).isAnyOf(null, 0)
                }
            }
            assertThat(f.client.exchange(clean)).isNotNull()
        }
    }

    @Test fun maliciousFakeTvAtUntrustedEndpointCannotProduceValidGcmReceipt() {
        fixture().use { f ->
            f.pairWithTrustedTvAction()
            PhoneProtectedProbeFactory(f.scanned, f.phone, elapsed).use { sender ->
                sender.create().use { request ->
                    val outbound = request.requestBytes()
                    val sample = checkNotNull(Lc04WireLab.decode(outbound))
                    val fakeBody = Lc04ReceiptBody(
                        1, ByteArray(32) { 9 }, ByteArray(12) { 7 },
                        ByteArray(64) { 11 },
                    )
                    val forged = Lc04WireLab.encode(Lc04WireFrame(
                        Lc04WireKind.SEALED_TV_RECEIPT,
                        sample.tvInvitationId(), sample.tvPairingId(),
                        sample.tvHpkeFingerprint(), sample.phoneKeyFingerprint(),
                        Lc04TypedWire.encodeReceipt(fakeBody),
                    ))
                    val listener = ServerSocket(0, 1,
                        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                    val attacker = Thread({
                        listener.use {
                            val conn = it.accept()
                            conn.use { s ->
                                s.soTimeout = 2_000
                                Lc04LengthPrefix.read(DataInputStream(s.getInputStream()), false)
                                Lc04LengthPrefix.write(
                                    DataOutputStream(s.getOutputStream()), forged,
                                )
                            }
                        }
                    }, "muxtv-hostile-fake-TV").apply { isDaemon = true; start() }
                    try {
                        val unchecked = checkNotNull(
                            Lc04TcpPhoneExchange.loopbackForTests(listener.localPort)
                                .exchange(outbound),
                        )
                        // A well-formed TCP response is not authenticated TV identity.
                        assertThat(request.verifyReply(unchecked)).isFalse()
                        val original = checkNotNull(f.client.exchange(outbound))
                        assertThat(request.verifyReply(original)).isTrue()
                    } finally {
                        listener.close()
                        attacker.join(2_000)
                    }
                }
            }
        }
    }

    @Test fun explicitStopAndQrExpiryRevokeEvenWithRememberedIpPort() {
        fixture().use { f ->
            val offer = Lc04PublicEnrollmentWire.phoneOffer(f.scanned, f.phone)
            f.server.close() // TV-side network change/stop must rotate QR
            assertThat(f.client.exchange(offer)).isNull()
            assertThat(f.tv.presentation(now)).isNull()
        }
        now = 2_000
        fixture().use { f ->
            val offer = Lc04PublicEnrollmentWire.phoneOffer(f.scanned, f.phone)
            now += TvHpkeQrSession.TTL_MILLIS
            assertThat(f.client.exchange(offer)).isNull()
            assertThat(f.tv.presentation(now)).isNull()
        }
    }

    @Test fun rejectsUntrustedDnsLoopbackWildcardPublicAndNoncanonicalAddresses() {
        for (forbidden in listOf(
            "localhost", "127.0.0.1", "0.0.0.0", "8.8.8.8", "169.254.0.1",
            "192.168.01.2", "192.168.1.256", "172.15.0.1", "172.32.0.1",
            "10.0.0.1 ", "10.0.0.1:4444", "10.0.0.1%eth0", "::1", "",
        )) assertThat(Lc04PrivateIpv4.parse(forbidden)).isNull()
        for (valid in listOf("10.0.0.1", "172.16.0.2", "172.31.255.254",
            "192.168.1.9", "10.255.255.254"
        )) assertThat(Lc04PrivateIpv4.parse(valid)).isNotNull()
    }

    @Test fun fixedPeerBudgetPreventsUnlimitedPerIpGlobalLoadAndClockRollback() {
        val limiter = BoundedPeerBudget(elapsed)
        repeat(12) { assertThat(limiter.permit("10.0.0.1")).isTrue() }
        assertThat(limiter.permit("10.0.0.1")).isFalse()
        repeat(12) { assertThat(limiter.permit("10.0.0.2")).isTrue() }
        repeat(12) { assertThat(limiter.permit("10.0.0.3")).isTrue() }
        repeat(12) { assertThat(limiter.permit("10.0.0.4")).isTrue() }
        assertThat(limiter.permit("10.0.0.5")).isFalse()
        now += 60_000
        assertThat(limiter.permit("10.0.0.1")).isTrue()
        now--
        assertThat(limiter.permit("10.0.0.1")).isFalse()
        now++
        assertThat(limiter.permit("10.0.0.1")).isFalse()
    }
}

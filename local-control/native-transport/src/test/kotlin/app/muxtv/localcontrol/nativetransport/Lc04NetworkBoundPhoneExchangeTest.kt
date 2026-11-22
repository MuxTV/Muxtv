package app.muxtv.localcontrol.nativetransport

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

/** GitHub-hosted real loopback sockets, Android-specific Network.bindSocket injected. */
class Lc04NetworkBoundPhoneExchangeTest {
    @Test fun binderRunsBeforeConnectAndActualTvChallengeCanTraverseBoundTcp() {
        val now = 1_000L
        val tv = TvHpkeQrSession.create(now)
        val phone = PhoneSigningIdentity.generate()
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now)).qrValue)
        val server = Lc04TvTcpServer.startLoopbackForTests(tv) { now }
        val binderCalls = AtomicInteger()
        val client = Lc04TcpPhoneExchange.loopbackForTests(server.port) { socket ->
            assertThat(socket.isConnected).isFalse()
            assertThat(socket.isClosed).isFalse()
            binderCalls.incrementAndGet()
        }
        try {
            val offer = Lc04PublicEnrollmentWire.phoneOffer(qr, phone)
            val challenge = checkNotNull(client.exchange(offer))
            assertThat(Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                qr, phone, challenge,
            )).isNotNull()
            assertThat(binderCalls.get()).isEqualTo(1)
            offer.fill(0)
            challenge.fill(0)
        } finally {
            client.close()
            server.close()
            phone.close()
            tv.close()
        }
    }

    @Test fun binderFailureCannotFallBackToUnboundSocket() {
        val now = 1_000L
        val tv = TvHpkeQrSession.create(now)
        val phone = PhoneSigningIdentity.generate()
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now)).qrValue)
        val server = Lc04TvTcpServer.startLoopbackForTests(tv) { now }
        val called = AtomicInteger()
        val client = Lc04TcpPhoneExchange.loopbackForTests(server.port) { socket ->
            assertThat(socket.isConnected).isFalse()
            called.incrementAndGet()
            throw IOException("network revoked before bind")
        }
        try {
            val offer = Lc04PublicEnrollmentWire.phoneOffer(qr, phone)
            assertThat(client.exchange(offer)).isNull()
            assertThat(called.get()).isEqualTo(1)
            assertThat(tv.phoneEnrollmentGate { now }?.pendingFingerprintForTv()).isNull()
            offer.fill(0)
            client.close()
            assertThat(client.exchange(Lc04PublicEnrollmentWire.phoneOffer(qr, phone))).isNull()
        } finally {
            client.close()
            server.close()
            phone.close()
            tv.close()
        }
    }

    @Test fun productNetworkBoundFactoryRejectsAnyNonPrivateLiteralWithoutDns() {
        for (bad in listOf("127.0.0.1", "8.8.8.8", "10.0.00.1", "localhost", "::1")) {
            assertThat(runCatching {
                Lc04TcpPhoneExchange.forPrivateIpv4OnNetwork(bad, 4000) { }
            }.isFailure).isTrue()
        }
    }
}

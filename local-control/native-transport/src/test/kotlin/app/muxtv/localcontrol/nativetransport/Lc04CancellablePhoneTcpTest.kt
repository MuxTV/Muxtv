package app.muxtv.localcontrol.nativetransport

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.phoneflow.Lc04PhoneFrameExchange
import app.muxtv.localcontrol.phoneflow.Lc04PhoneProofFlow
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

/** Real, unresponsive TCP peers: close must interrupt I/O, not only revoke keys. */
class Lc04CancellablePhoneTcpTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val now: () -> Long = { 10_000L }

    private fun blockedServer(
        accepted: CountDownLatch,
        release: CountDownLatch,
    ): Pair<ServerSocket, Thread> {
        val listener = ServerSocket(0, 1, loopback)
        val serverThread = Thread({
            try {
                listener.use { server ->
                    server.accept().use { ignored ->
                        accepted.countDown()
                        release.await(7, TimeUnit.SECONDS)
                    }
                }
            } catch (_: java.io.IOException) {
                // Test cleanup may close the listener; never a protocol reply.
            }
        }, "muxtv-hostile-stalled-peer").apply { isDaemon = true; start() }
        return listener to serverThread
    }

    @Test fun closeInterruptsBlockedTcpReadAndPermanentlyDeniesFurtherConnections() {
        val tv = TvHpkeQrSession.create(now())
        val identity = PhoneSigningIdentity.generate()
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now())).qrValue)
        val offer = Lc04PublicEnrollmentWire.phoneOffer(qr, identity)
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (listener, server) = blockedServer(accepted, release)
        val client = Lc04TcpPhoneExchange.loopbackForTests(listener.localPort)
        val reply = AtomicReference<ByteArray?>()
        val caller = Thread({ reply.set(client.exchange(offer)) }, "muxtv-blocked-client")
        try {
            caller.start()
            assertThat(accepted.await(4, TimeUnit.SECONDS)).isTrue()
            client.close() // simulates Activity.onStop while waiting for TV
            caller.join(1_800)
            assertThat(caller.isAlive).isFalse()
            assertThat(reply.get()).isNull()
            assertThat(client.exchange(offer)).isNull()
            client.close() // idempotent revoke
        } finally {
            client.close()
            release.countDown()
            listener.close()
            caller.join(4_000)
            server.join(4_000)
            identity.close()
            tv.close()
            offer.fill(0)
        }
    }

    @Test fun secondExchangeCannotRaceActiveConnectionOrReopenRevokedSocket() {
        val tv = TvHpkeQrSession.create(now())
        val identity = PhoneSigningIdentity.generate()
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now())).qrValue)
        val offer = Lc04PublicEnrollmentWire.phoneOffer(qr, identity)
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (listener, server) = blockedServer(accepted, release)
        val client = Lc04TcpPhoneExchange.loopbackForTests(listener.localPort)
        val worker = Thread({ client.exchange(offer) }, "muxtv-first-exchange")
        try {
            worker.start()
            assertThat(accepted.await(4, TimeUnit.SECONDS)).isTrue()
            // Single-flight check must reject immediately, not enter a
            // second blocked accept/connect against the hostile server.
            assertThat(client.exchange(offer)).isNull()
            client.close()
            worker.join(1_800)
            assertThat(worker.isAlive).isFalse()
            assertThat(client.exchange(offer)).isNull()
        } finally {
            client.close()
            release.countDown()
            listener.close()
            worker.join(4_000)
            server.join(4_000)
            identity.close()
            tv.close()
            offer.fill(0)
        }
    }

    @Test fun closingPhoneProofFlowCancelsItsOwnedRealTcpExchange() {
        val tv = TvHpkeQrSession.create(now())
        val identity = PhoneSigningIdentity.generate()
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now())).qrValue)
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (listener, server) = blockedServer(accepted, release)
        val client = Lc04TcpPhoneExchange.loopbackForTests(listener.localPort)
        val ownedTransport = object : Lc04PhoneFrameExchange {
            override fun exchange(frame: ByteArray): ByteArray? = client.exchange(frame)
            override fun close() = client.close()
        }
        val flow = Lc04PhoneProofFlow(qr, identity, ownedTransport, now)
        val result = AtomicReference<app.muxtv.localcontrol.phoneflow.Lc04PhoneReview?>()
        val worker = Thread({ result.set(flow.offer()) }, "muxtv-phone-onstop")
        try {
            worker.start()
            assertThat(accepted.await(4, TimeUnit.SECONDS)).isTrue()
            flow.close() // Activity.onStop must interrupt an in-flight offer
            worker.join(1_800)
            assertThat(worker.isAlive).isFalse()
            assertThat(result.get()).isNull()
            assertThat(flow.hasAuthenticatedReceipt()).isFalse()
            assertThat(client.exchange(Lc04PublicEnrollmentWire.phoneOffer(qr, identity))).isNull()
        } finally {
            flow.close()
            release.countDown()
            listener.close()
            worker.join(4_000)
            server.join(4_000)
            identity.close()
            tv.close()
        }
    }
}

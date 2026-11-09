package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

class Lc04MxH1PrivateLanTest {
    private class Fixture : AutoCloseable {
        val clock = AtomicLong(10_000L)
        val tv = TvHpkeQrSession.create(clock.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(clock.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock::get)
        val gate = checkNotNull(tv.phoneEnrollmentGate(clock::get))
        var server: Lc04MxH1PrivateLanTv? = null
        var client: Lc04MxH1PrivateLanPhone? = null

        fun approve() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, clock.get())).isTrue()
        }

        fun listen() = checkNotNull(
            Lc04MxH1PrivateLanTv.startLoopbackForTests(tv, clock::get).also {
                server = it
            },
        )
        fun connect(port: Int, binder: (Socket) -> Unit = {}): Lc04MxH1PrivateLanPhone =
            Lc04MxH1PrivateLanPhone.loopbackForTests(port, binder).also {
                client = it
            }

        override fun close() {
            client?.close()
            server?.close()
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        val f = Fixture()
        try { action(f) } finally { f.close() }
    }

    private fun awaitDenial(f: Fixture) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (f.tv.presentation(f.clock.get()) != null &&
            System.nanoTime() < deadline
        ) Thread.sleep(10)
        assertThat(f.tv.presentation(f.clock.get())).isNull()
    }

    @Test fun noTcpListenerBeforeExactTvLocalEd25519Approval() =
        withFixture { f ->
            assertThat(f.tv.hasApprovedPhoneForSyntheticMxH1(f.clock.get())).isFalse()
            assertThat(runCatching {
                Lc04MxH1PrivateLanTv.startLoopbackForTests(f.tv, f.clock::get)
            }.isFailure).isTrue()
            f.approve()
            assertThat(f.tv.hasApprovedPhoneForSyntheticMxH1(f.clock.get())).isTrue()
        }

    @Test fun realLoopbackFourFrameExchangeAuthenticatesFinishedAndKeepsSameQr() =
        withFixture { f ->
            f.approve()
            val publicQr = checkNotNull(f.tv.presentation(f.clock.get())).qrValue
            val tv = f.listen()
            val bindCalled = AtomicBoolean(false)
            val phone = f.connect(tv.port) { socket ->
                assertThat(socket.isConnected).isFalse()
                bindCalled.set(true)
            }
            assertThat(phone.complete(f.phone)).isTrue()
            assertThat(bindCalled.get()).isTrue()
            assertThat(tv.hasSyntheticNetworkProof()).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
            assertThat(checkNotNull(f.tv.presentation(f.clock.get())).qrValue)
                .isEqualTo(publicQr)
            assertThat(phone.complete(f.phone)).isFalse()
            tv.close()
            assertThat(f.tv.presentation(f.clock.get())).isNull()
        }

    @Test fun privateLanFactoryRejectsLoopbackWildcardDnsAndPublicIpv4() =
        withFixture { f ->
            f.approve()
            for (address in listOf("127.0.0.1", "0.0.0.0", "localhost",
                "8.8.8.8", "1.1.1.1", "192.168.1.01", "::1")) {
                assertThat(runCatching {
                    Lc04MxH1PrivateLanTv.startOnApprovedPrivateIpv4(
                        address, f.tv, f.clock::get,
                    )
                }.isFailure).isTrue()
                assertThat(runCatching {
                    Lc04MxH1PrivateLanPhone.forPrivateIpv4OnNetwork(
                        address, 7777,
                    ) {}
                }.isFailure).isTrue()
            }
            assertThat(runCatching {
                Lc04MxH1PrivateLanPhone.forPrivateIpv4OnNetwork(
                    "192.168.1.10", 0,
                ) {}
            }.isFailure).isTrue()
            assertThat(runCatching {
                Lc04MxH1PrivateLanPhone.forPrivateIpv4OnNetwork(
                    "192.168.1.10", 65536,
                ) {}
            }.isFailure).isTrue()
            val restricted = Lc04MxH1PrivateLanPhone.forPrivateIpv4OnNetwork(
                "192.168.1.10", 9876,
            ) {}
            assertThat(restricted.toString()).contains("[ADDRESS_REDACTED]")
            restricted.close()
        }

    @Test fun bindingFailureBeforeConnectRevokesSeedAndNeverAuthenticatesTv() =
        withFixture { f ->
            f.approve()
            val server = f.listen()
            val phone = f.connect(server.port) {
                throw IOException("selected Wi-Fi network lost")
            }
            assertThat(phone.complete(f.phone)).isFalse()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
        }

    @Test fun hostileFirstFrameInvalidLengthClosesOriginalTvOwner() =
        withFixture { f ->
            f.approve()
            val server = f.listen()
            Socket(loopback(), server.port).use { socket ->
                socket.soTimeout = 3_000
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(1_000_000)
                    flush()
                }
                socket.shutdownOutput()
            }
            awaitDenial(f)
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
        }

    @Test fun ownerCloseInterruptsPartialPeerAndCancelsProof() =
        withFixture { f ->
            f.approve()
            val server = f.listen()
            Socket(loopback(), server.port).use { socket ->
                socket.soTimeout = 3_000
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(200)
                    writeByte(0x4d)
                    flush()
                }
                server.close() // Android TV onStop / route invalidation
                try {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1)
                } catch (_: IOException) { /* RST also fails closed */ }
            }
            assertThat(f.tv.presentation(f.clock.get())).isNull()
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
        }

    @Test fun phoneCloseInterruptsSilentPeerAndRevokesAttempt() =
        withFixture { f ->
            f.approve()
            val silent = ServerSocket(0, 1, loopback())
            val connected = CountDownLatch(1)
            val worker = Thread {
                try {
                    silent.use {
                        it.accept().use { socket ->
                            connected.countDown()
                            socket.soTimeout = 2_500
                            try { socket.getInputStream().read() }
                            catch (_: IOException) {}
                        }
                    }
                } catch (_: IOException) {}
            }.apply { isDaemon = true; start() }
            val client = f.connect(silent.localPort)
            val returned = CountDownLatch(1)
            val value = AtomicBoolean(true)
            val attempt = Thread {
                try { value.set(client.complete(f.phone)) }
                finally { returned.countDown() }
            }.apply { isDaemon = true; start() }
            try {
                assertThat(connected.await(3, TimeUnit.SECONDS)).isTrue()
                client.close()
                assertThat(returned.await(3, TimeUnit.SECONDS)).isTrue()
                assertThat(value.get()).isFalse()
                assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            } finally {
                client.close()
                runCatching { silent.close() }
                worker.join(2_000)
                attempt.join(2_000)
            }
        }

    @Test fun expiredOriginalQrDeniesNativeMxH1WithoutNewKeyOwner() =
        withFixture { f ->
            f.approve()
            f.clock.addAndGet(TvHpkeQrSession.TTL_MILLIS)
            assertThat(f.tv.hasApprovedPhoneForSyntheticMxH1(f.clock.get())).isFalse()
            assertThat(runCatching { f.listen() }.isFailure).isTrue()
        }

    private fun loopback(): InetAddress =
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
}

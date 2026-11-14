package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import com.google.common.truth.Truth.assertThat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/** Authenticated six-frame TCP using the ORIGINAL QR, never test-only keys. */
class Lc04MxH1SealedPrivateLanTvTest {
    private class Fixture : AutoCloseable {
        val now = AtomicLong(10_000L)
        val tv = TvHpkeQrSession.create(now.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, now::get)
        val gate = checkNotNull(tv.phoneEnrollmentGate(now::get))
        val bindCalled = AtomicBoolean(false)
        val revoked = AtomicInteger()
        var listener: Lc04MxH1SealedPrivateLanTv? = null
        var client: Lc04MxH1LeasedSealedPhone? = null

        fun approveOnOriginalTv() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val identity = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(identity.identity, now.get())).isTrue()
        }

        fun listen(): Lc04MxH1SealedPrivateLanTv =
            Lc04MxH1SealedPrivateLanTv.startLoopbackForHostTests(tv, now::get)
                .also { listener = it }

        fun clientFor(port: Int): Lc04MxH1LeasedSealedPhone =
            Lc04MxH1LeasedSealedPhone.withTestSocketForHostileJVM(
                privateIpv4 = "192.168.52.17",
                port = port,
                originalPhone = phone,
                elapsedMillis = now::get,
                selectedRouteEligible = { true },
                bindUnconnectedToSelectedNetwork = { socket ->
                    assertThat(socket.isConnected).isFalse()
                    bindCalled.set(true)
                },
                revokeOriginalQrAndKeys = { revoked.incrementAndGet() },
                testSocketSupplier = {
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            val original = endpoint as InetSocketAddress
                            if (original.address.hostAddress != "192.168.52.17" ||
                                original.port != port
                            ) throw IOException("Unexpected selected endpoint.")
                            super.connect(
                                InetSocketAddress(loopback(), port), timeout,
                            )
                        }
                    }
                },
            ).also { client = it }

        override fun close() {
            client?.close()
            listener?.close()
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    private fun awaitTvProof(tv: Lc04MxH1SealedPrivateLanTv) {
        // The phone observes TV EOF before the TV worker publishes its
        // completed state. Synchronize with the actual worker boundary.
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!tv.hasAuthenticatedSyntheticExchange() &&
            System.nanoTime() < until
        ) Thread.sleep(10)
        assertThat(tv.hasAuthenticatedSyntheticExchange()).isTrue()
    }

    private fun awaitRevoked(f: Fixture) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (f.tv.presentation(f.now.get()) != null &&
            System.nanoTime() < until
        ) Thread.sleep(10)
        assertThat(f.tv.presentation(f.now.get())).isNull()
    }

    @Test fun originalTvQrConsentIsMandatoryBeforeOpeningListener() = fixture { f ->
        assertThat(runCatching { f.listen() }.isFailure).isTrue()
        assertThat(runCatching {
            Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4(
                "192.168.52.17", f.tv, f.now::get,
            )
        }.isFailure).isTrue()
        f.approveOnOriginalTv()
        assertThat(f.tv.hasApprovedPhoneForSyntheticMxH1(f.now.get())).isTrue()
    }

    @Test fun sixActualTcpFramesWithApprovedPhoneAndExactQrSucceedOnce() =
        fixture { f ->
            f.approveOnOriginalTv()
            val originalQr = checkNotNull(f.tv.presentation(f.now.get())).qrValue
            val listener = f.listen()
            val phone = f.clientFor(listener.port)
            assertThat(phone.completeSyntheticExchange()).isTrue()
            assertThat(f.bindCalled.get()).isTrue()
            awaitTvProof(listener)
            assertThat(checkNotNull(f.tv.presentation(f.now.get())).qrValue)
                .isEqualTo(originalQr)
            assertThat(phone.completeSyntheticExchange()).isFalse()
            assertThat(f.revoked.get()).isEqualTo(1)
            listener.close()
            assertThat(f.tv.presentation(f.now.get())).isNull()
        }

    @Test fun privateFactoryRejectsHostnamesPublicWildcardLoopbackAndIpv6() =
        fixture { f ->
            f.approveOnOriginalTv()
            for (ip in listOf(
                "127.0.0.1", "0.0.0.0", "localhost", "8.8.8.8",
                "192.168.52.017", "1.1.1.1", "::1",
            )) {
                assertThat(runCatching {
                    Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4(
                        ip, f.tv, f.now::get,
                    )
                }.isFailure).isTrue()
            }
        }

    @Test fun invalidFirstFrameIsTerminalAndRevokesOriginalTvQr() =
        fixture { f ->
            f.approveOnOriginalTv()
            val listener = f.listen()
            Socket(loopback(), listener.port).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(1_000_000)
                    flush()
                }
                socket.shutdownOutput()
            }
            awaitRevoked(f)
            assertThat(listener.hasAuthenticatedSyntheticExchange()).isFalse()
        }

    @Test fun incompleteHandshakeInterruptedByTvOnStopCannotAuthenticate() =
        fixture { f ->
            f.approveOnOriginalTv()
            val listener = f.listen()
            Socket(loopback(), listener.port).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(200)
                    writeByte(0x4d)
                    flush()
                }
                listener.close()
                try {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1)
                } catch (_: IOException) {
                    // TCP reset is also a fail-closed abort.
                }
            }
            assertThat(f.tv.presentation(f.now.get())).isNull()
            assertThat(listener.hasAuthenticatedSyntheticExchange()).isFalse()
        }

    @Test fun expiredOriginalQrCannotStartSixFramePrivateListener() =
        fixture { f ->
            f.approveOnOriginalTv()
            f.now.addAndGet(TvHpkeQrSession.TTL_MILLIS)
            assertThat(runCatching { f.listen() }.isFailure).isTrue()
        }

    @Test fun forgedSealedFifthFrameCannotElicitTvReply() =
        fixture { f ->
            f.approveOnOriginalTv()
            val listener = f.listen()
            Socket(loopback(), listener.port).use { socket ->
                socket.soTimeout = 3_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val offer = checkNotNull(f.phone.createSignedSeedOffer())
                try {
                    Lc04MxH1FrameIo.write(
                        output, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                    )
                } finally { offer.fill(0) }

                val tvConfirm = Lc04MxH1FrameIo.read(
                    input, Lc04HandoffKind.TV_KEY_CONFIRM,
                )
                val phoneConfirm = try {
                    checkNotNull(f.phone.acceptTvConfirmationAwaitingFinished(tvConfirm))
                } finally { tvConfirm.fill(0) }
                try {
                    Lc04MxH1FrameIo.write(
                        output, phoneConfirm, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    )
                } finally { phoneConfirm.fill(0) }

                val finished = Lc04MxH1FrameIo.read(
                    input, Lc04HandoffKind.TV_FINISHED,
                )
                try {
                    assertThat(f.phone.acceptTvFinishedForProbeLab(finished, f.now::get))
                        .isTrue()
                } finally { finished.fill(0) }

                val forgedFifth = checkNotNull(f.phone.sealMxH1SyntheticProbeForLab())
                try {
                    forgedFifth[forgedFifth.lastIndex] =
                        (forgedFifth.last().toInt() xor 1).toByte()
                    Lc04MxH1LeasedSealedFrameIo.write(output, forgedFifth)
                    socket.shutdownOutput()
                } finally { forgedFifth.fill(0) }

                // The TV must not emit ANY sixth record after an invalid F5.
                try {
                    assertThat(input.read()).isEqualTo(-1)
                } catch (_: IOException) {
                    // A peer reset is also a safe terminal rejection.
                }
            }
            awaitRevoked(f)
            assertThat(listener.hasAuthenticatedSyntheticExchange()).isFalse()
        }

    private companion object {
        fun loopback(): InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}

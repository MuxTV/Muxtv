package app.muxtv.localcontrol.mxh1tcp

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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * Real socket/hostile relay evidence, but loopback only and SYNTHETIC probes.
 * Never a production TV/phone LAN or credential/provisioning test.
 */
class Lc04MxH1SealedLoopbackTcpTest {
    private class Fixture : AutoCloseable {
        val clock = AtomicLong(10_000)
        val tv = TvHpkeQrSession.create(clock.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(clock.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock::get)
        val gate = checkNotNull(tv.phoneEnrollmentGate(clock::get))
        var server: Lc04MxH1SealedLoopbackTv? = null
        var client: Lc04MxH1SealedLoopbackPhone? = null

        fun approve() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val identity = (gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified).identity
            assertThat(tv.activateVerifiedPhone(identity, clock.get())).isTrue()
        }

        fun start() = Lc04MxH1SealedLoopbackTv.startLoopbackForTests(
            tv, clock::get,
        ).also { server = it }

        fun phoneClient(port: Int) = Lc04MxH1SealedLoopbackPhone.loopbackForTests(
            port, clock::get,
        ).also { client = it }

        /** Actual approved TV QR and four authenticated frames over this socket. */
        fun fourFrames(socket: Socket) {
            socket.soTimeout = 3_500
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val offer = checkNotNull(phone.createSignedSeedOffer())
            try {
                Lc04MxH1LengthFrame.write(
                    output, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                )
            } finally { offer.fill(0) }
            val tvConfirm = Lc04MxH1LengthFrame.read(
                input, Lc04HandoffKind.TV_KEY_CONFIRM,
            )
            val confirmation = try {
                checkNotNull(phone.acceptTvConfirmationAwaitingFinished(tvConfirm))
            } finally { tvConfirm.fill(0) }
            try {
                Lc04MxH1LengthFrame.write(
                    output, confirmation, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                )
            } finally { confirmation.fill(0) }
            val finished = Lc04MxH1LengthFrame.read(
                input, Lc04HandoffKind.TV_FINISHED,
            )
            try {
                assertThat(phone.acceptTvFinishedForProbeLab(finished, clock::get))
                    .isTrue()
            } finally { finished.fill(0) }
        }

        fun dial(port: Int) = Socket(loopback(), port)

        fun awaitTvRevoked() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (tv.presentation(clock.get()) != null && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertThat(tv.presentation(clock.get())).isNull()
        }

        override fun close() {
            client?.close()
            server?.close()
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally { f.close() }
    }

    @Test fun sixRealTcpFramesCompleteOnlyAfterPhoneAndTvEof() = fixture { f ->
        f.approve()
        val tv = f.start()
        val client = f.phoneClient(tv.port)
        assertThat(client.complete(f.phone)).isTrue()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!tv.hasAuthenticatedSyntheticTcpExchange() &&
            System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(tv.hasAuthenticatedSyntheticTcpExchange()).isTrue()
        assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
        assertThat(f.tv.hasSyntheticMxH1Proof(f.clock.get())).isTrue()
        assertThat(client.complete(f.phone)).isFalse() // one-use and no reconnection
    }

    @Test fun noListenerBeforeTvLocalApprovedSigner() = fixture { f ->
        assertThat(runCatching {
            Lc04MxH1SealedLoopbackTv.startLoopbackForTests(f.tv, f.clock::get)
        }.isFailure).isTrue()
    }

    @Test fun tamperedFifthAeadTagDeniesSixthAndRevokesOriginalQr() = fixture { f ->
        f.approve()
        val tv = f.start()
        f.dial(tv.port).use { socket ->
            f.fourFrames(socket)
            val wire = checkNotNull(f.phone.sealMxH1SyntheticProbeForLab())
            wire[wire.lastIndex] = (wire.last().toInt() xor 1).toByte()
            Lc04MxH1SealedFrameIo.write(DataOutputStream(socket.getOutputStream()), wire)
            socket.shutdownOutput()
            try {
                assertThat(socket.getInputStream().read()).isEqualTo(-1)
            } catch (_: IOException) { /* RST also denies */ }
        }
        f.awaitTvRevoked()
        assertThat(tv.hasAuthenticatedSyntheticTcpExchange()).isFalse()
    }

    @Test fun extraPipelinedByteAfterValidFifthIsTerminal() = fixture { f ->
        f.approve()
        val tv = f.start()
        f.dial(tv.port).use { socket ->
            f.fourFrames(socket)
            val wire = checkNotNull(f.phone.sealMxH1SyntheticProbeForLab())
            val out = DataOutputStream(socket.getOutputStream())
            Lc04MxH1SealedFrameIo.write(out, wire)
            out.writeByte(0x7f)
            out.flush()
            socket.shutdownOutput()
            try {
                assertThat(socket.getInputStream().read()).isEqualTo(-1)
            } catch (_: IOException) { /* RST is fail-closed */ }
        }
        f.awaitTvRevoked()
        assertThat(tv.hasAuthenticatedSyntheticTcpExchange()).isFalse()
    }

    @Test fun duplicateFifthFrameBeforeHalfCloseIsRejected() = fixture { f ->
        f.approve()
        val tv = f.start()
        f.dial(tv.port).use { socket ->
            f.fourFrames(socket)
            val wire = checkNotNull(f.phone.sealMxH1SyntheticProbeForLab())
            val out = DataOutputStream(socket.getOutputStream())
            Lc04MxH1SealedFrameIo.write(out, wire)
            Lc04MxH1SealedFrameIo.write(out, wire)
            socket.shutdownOutput()
        }
        f.awaitTvRevoked()
        assertThat(tv.hasAuthenticatedSyntheticTcpExchange()).isFalse()
    }

    @Test fun invalidRecordSizeAndTruncationRevokeKeys() {
        for (invalidSize in listOf(0, 103, 105, 0x0100_0000)) fixture { f ->
            f.approve()
            val tv = f.start()
            f.dial(tv.port).use { socket ->
                f.fourFrames(socket)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(invalidSize)
                out.flush()
                socket.shutdownOutput()
            }
            f.awaitTvRevoked()
        }
        fixture { f ->
            f.approve()
            val tv = f.start()
            f.dial(tv.port).use { socket ->
                f.fourFrames(socket)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(104)
                out.write(byteArrayOf(0x4d, 0x58))
                out.flush()
                socket.shutdownOutput()
            }
            f.awaitTvRevoked()
        }
    }

    @Test fun tvOnStopDuringPartialFifthFrameCancelsAndRevokes() = fixture { f ->
        f.approve()
        val tv = f.start()
        f.dial(tv.port).use { socket ->
            f.fourFrames(socket)
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(104)
            out.writeByte(0x4d)
            out.flush()
            tv.close()
            try {
                assertThat(socket.getInputStream().read()).isEqualTo(-1)
            } catch (_: IOException) {}
        }
        assertThat(f.tv.presentation(f.clock.get())).isNull()
        assertThat(tv.hasAuthenticatedSyntheticTcpExchange()).isFalse()
    }

    @Test fun delayedFifthAfterQrExpiryCannotProduceSixth() = fixture { f ->
        f.approve()
        val tv = f.start()
        f.dial(tv.port).use { socket ->
            f.fourFrames(socket)
            f.clock.addAndGet(TvHpkeQrSession.TTL_MILLIS)
            val out = DataOutputStream(socket.getOutputStream())
            val wire = f.phone.sealMxH1SyntheticProbeForLab()
            assertThat(wire).isNull()
            socket.shutdownOutput()
        }
        f.awaitTvRevoked()
    }

    /**
     * Actual hostile TCP intermediary between client and TV.
     * It forwards the four MXH1 frames unchanged and corrupts one
     * authenticated byte of the fifth MXR1 record. Neither peer accepts
     * the altered sixth-frame absence as a successful exchange.
     */
    @Test fun activeMitmFifthTagCorruptionIsNotAccepted() = fixture { f ->
        f.approve()
        val tv = f.start()
        val relay = ServerSocket(0, 1, loopback())
        val finished = CountDownLatch(1)
        val mediator = Thread {
            try {
                relay.use { listener ->
                    listener.accept().use { incoming ->
                        Socket(loopback(), tv.port).use { outgoing ->
                            incoming.soTimeout = 4_000
                            outgoing.soTimeout = 4_000
                            val pi = DataInputStream(incoming.getInputStream())
                            val po = DataOutputStream(incoming.getOutputStream())
                            val ti = DataInputStream(outgoing.getInputStream())
                            val to = DataOutputStream(outgoing.getOutputStream())

                            val offer = Lc04MxH1LengthFrame.read(
                                pi, Lc04HandoffKind.SIGNED_SEED_OFFER,
                            )
                            Lc04MxH1LengthFrame.write(
                                to, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                            )
                            offer.fill(0)
                            val tvTag = Lc04MxH1LengthFrame.read(
                                ti, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                            Lc04MxH1LengthFrame.write(
                                po, tvTag, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                            tvTag.fill(0)
                            val phTag = Lc04MxH1LengthFrame.read(
                                pi, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                            )
                            Lc04MxH1LengthFrame.write(
                                to, phTag, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                            )
                            phTag.fill(0)
                            val tvFinished = Lc04MxH1LengthFrame.read(
                                ti, Lc04HandoffKind.TV_FINISHED,
                            )
                            Lc04MxH1LengthFrame.write(
                                po, tvFinished, Lc04HandoffKind.TV_FINISHED,
                            )
                            tvFinished.fill(0)
                            val fifth = Lc04MxH1SealedFrameIo.read(pi)
                            fifth[fifth.lastIndex] = (fifth.last().toInt() xor 1).toByte()
                            Lc04MxH1SealedFrameIo.write(to, fifth)
                            fifth.fill(0)
                            outgoing.shutdownOutput()
                            // No TV sixth reply after failed GCM verification.
                            runCatching { ti.read() }
                            incoming.shutdownOutput()
                        }
                    }
                }
            } catch (_: IOException) {
                // A RST / closed peer also proves the exchange was denied.
            } finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        val client = f.phoneClient(relay.localPort)
        try {
            assertThat(client.complete(f.phone)).isFalse()
            assertThat(finished.await(6, TimeUnit.SECONDS)).isTrue()
            f.awaitTvRevoked()
            assertThat(tv.hasAuthenticatedSyntheticTcpExchange()).isFalse()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
        } finally {
            runCatching { relay.close() }
            mediator.join(2_000)
        }
    }

    private fun loopback(): InetAddress =
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
}

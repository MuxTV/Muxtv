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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

/**
 * S2J2C2A: real TCP and real QR/HPKE/Tink/MXR1; selected Android Network is
 * simulated by a strict, observable socket/route binder, NOT device-qualified.
 */
class Lc04MxH1LeasedSealedPhoneTest {
    private enum class TvBehavior {
        HONEST, TAMPER_FINISHED, TAMPER_SIXTH, EXTRA_AFTER_SIXTH,
        TRUNCATE_SIXTH, MISSING_SIXTH, REVOKE_ROUTE_AFTER_TV_CONFIRM,
        HOLD_BEFORE_TV_CONFIRM, WRONG_SIXTH_LENGTH,
    }

    private class Fixture : AutoCloseable {
        val now = AtomicLong(10_000)
        val allowed = AtomicBoolean(true)
        val binds = AtomicInteger()
        val revokes = AtomicInteger()
        val destinationCalls = AtomicInteger()
        val requestedEndpoint = AtomicReference<InetSocketAddress?>()
        val offerReceived = CountDownLatch(1)
        val releaseTvConfirm = CountDownLatch(1)
        val tv = TvHpkeQrSession.create(now.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, now::get)
        val gate = checkNotNull(tv.phoneEnrollmentGate(now::get))
        var listener: ServerSocket? = null
        var worker: Thread? = null
        var client: Lc04MxH1LeasedSealedPhone? = null
        val serverAcceptedFifth = AtomicBoolean(false)

        fun approveOnOriginalTv() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, now.get())).isTrue()
        }

        fun startServer(behavior: TvBehavior = TvBehavior.HONEST): Int {
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            listener = server
            worker = Thread({
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val offer = Lc04MxH1FrameIo.read(
                            input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                        )
                        val confirmation = try {
                            tv.acceptSyntheticMxH1SeedOffer(offer, now.get())
                        } finally { offer.fill(0) }
                        if (confirmation == null) return@Thread
                        try {
                            offerReceived.countDown()
                            if (behavior == TvBehavior.HOLD_BEFORE_TV_CONFIRM) {
                                releaseTvConfirm.await(4, TimeUnit.SECONDS)
                            }
                            Lc04MxH1FrameIo.write(
                                output, confirmation, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                            if (behavior == TvBehavior.REVOKE_ROUTE_AFTER_TV_CONFIRM) {
                                allowed.set(false)
                            }
                        } finally { confirmation.fill(0) }
                        val third = Lc04MxH1FrameIo.read(
                            input, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                        )
                        val finished = try {
                            tv.finishSyntheticMxH1ForProbeLab(third, now::get)
                        } finally { third.fill(0) }
                        if (finished == null) return@Thread
                        try {
                            if (behavior == TvBehavior.TAMPER_FINISHED) {
                                finished[finished.lastIndex] =
                                    (finished.last().toInt() xor 1).toByte()
                            }
                            Lc04MxH1FrameIo.write(
                                output, finished, Lc04HandoffKind.TV_FINISHED,
                            )
                        } finally { finished.fill(0) }
                        if (behavior == TvBehavior.TAMPER_FINISHED) return@Thread

                        val fifth = Lc04MxH1LeasedSealedFrameIo.read(input)
                        try {
                            val ended = input.read() == -1
                            val accepted = ended &&
                                tv.acceptMxH1SyntheticProbeForLab(fifth, now.get())
                            if (!accepted) return@Thread
                            serverAcceptedFifth.set(true)
                        } finally { fifth.fill(0) }

                        if (behavior == TvBehavior.MISSING_SIXTH) return@Thread
                        if (behavior == TvBehavior.WRONG_SIXTH_LENGTH) {
                            output.writeInt(103)
                            output.flush()
                            return@Thread
                        }
                        val sixth = tv.sealMxH1SyntheticProbeForLab(now.get())
                            ?: return@Thread
                        try {
                            if (behavior == TvBehavior.TAMPER_SIXTH) {
                                sixth[sixth.lastIndex] = (sixth.last().toInt() xor 1).toByte()
                            }
                            if (behavior == TvBehavior.TRUNCATE_SIXTH) {
                                output.writeInt(104)
                                output.write(sixth, 0, 9)
                                output.flush()
                            } else {
                                Lc04MxH1LeasedSealedFrameIo.write(output, sixth)
                            }
                            if (behavior == TvBehavior.EXTRA_AFTER_SIXTH) {
                                output.writeByte(0x7f)
                                output.flush()
                            }
                            socket.shutdownOutput()
                        } finally { sixth.fill(0) }
                    }
                } catch (_: IOException) {
                    // Client intentionally aborted a hostile/incomplete exchange.
                } catch (_: InterruptedException) {
                    // Test fixture shutdown; no recovery/reconnection.
                } finally {
                    server.close()
                }
            }, "lc04-leased-six-frame-hostile-tv").apply { isDaemon = true; start() }
            return server.localPort
        }

        /**
         * Test-only socket redirects to numeric 127.0.0.1, but records the
         * ACTUAL production endpoint requested by the phone. Production
         * factory always creates Socket() without such a redirect.
         */
        fun candidate(
            loopbackPort: Int,
            fakeBinder: (Socket) -> Unit = { binds.incrementAndGet() },
        ): Lc04MxH1LeasedSealedPhone {
            val result = Lc04MxH1LeasedSealedPhone.withTestSocketForHostileJVM(
                privateIpv4 = "192.168.52.17",
                port = 49831,
                originalPhone = phone,
                elapsedMillis = now::get,
                selectedRouteEligible = allowed::get,
                bindUnconnectedToSelectedNetwork = fakeBinder,
                revokeOriginalQrAndKeys = { revokes.incrementAndGet() },
                testSocketSupplier = {
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            val original = endpoint as InetSocketAddress
                            requestedEndpoint.set(original)
                            destinationCalls.incrementAndGet()
                            if (original.address.hostAddress != "192.168.52.17" ||
                                original.port != 49831
                            ) throw IOException("Unexpected selected-route destination")
                            super.connect(
                                InetSocketAddress(
                                    InetAddress.getLoopbackAddress(), loopbackPort,
                                ), timeout,
                            )
                        }
                    }
                },
            )
            client = result
            return result
        }

        override fun close() {
            releaseTvConfirm.countDown()
            client?.close()
            runCatching { listener?.close() }
            worker?.join(3_500)
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally { f.close() }
    }

    @Test fun `six actual TCP frames succeed only through exact selected endpoint`() =
        fixture { f ->
            f.approveOnOriginalTv()
            val port = f.startServer()
            val phone = f.candidate(port)
            assertThat(phone.completeSyntheticExchange()).isTrue()
            f.worker?.join(3_500)
            assertThat(f.serverAcceptedFifth.get()).isTrue()
            assertThat(f.destinationCalls.get()).isEqualTo(1)
            assertThat(f.requestedEndpoint.get()?.address?.hostAddress)
                .isEqualTo("192.168.52.17")
            assertThat(f.requestedEndpoint.get()?.port).isEqualTo(49831)
            assertThat(f.binds.get()).isEqualTo(1)
            assertThat(f.revokes.get()).isEqualTo(1)
            assertThat(phone.completeSyntheticExchange()).isFalse()
            assertThat(f.revokes.get()).isEqualTo(1)
        }

    @Test fun `forged final MXH1 MAC never sends a fifth probe`() = fixture { f ->
        f.approveOnOriginalTv()
        val phone = f.candidate(f.startServer(TvBehavior.TAMPER_FINISHED))
        assertThat(phone.completeSyntheticExchange()).isFalse()
        assertThat(f.serverAcceptedFifth.get()).isFalse()
        assertThat(f.revokes.get()).isEqualTo(1)
    }

    @Test fun `corrupted encrypted TV sixth record is rejected`() = fixture { f ->
        f.approveOnOriginalTv()
        val phone = f.candidate(f.startServer(TvBehavior.TAMPER_SIXTH))
        assertThat(phone.completeSyntheticExchange()).isFalse()
        f.worker?.join(3_500)
        assertThat(f.serverAcceptedFifth.get()).isTrue()
        assertThat(f.revokes.get()).isEqualTo(1)
    }

    @Test fun `pipelined bytes after final sealed reply violate EOF`() = fixture { f ->
        f.approveOnOriginalTv()
        val phone = f.candidate(f.startServer(TvBehavior.EXTRA_AFTER_SIXTH))
        assertThat(phone.completeSyntheticExchange()).isFalse()
        assertThat(f.revokes.get()).isEqualTo(1)
    }

    @Test fun `truncated or incorrectly sized sixth frame fails closed`() {
        for (mode in listOf(
            TvBehavior.TRUNCATE_SIXTH, TvBehavior.WRONG_SIXTH_LENGTH,
            TvBehavior.MISSING_SIXTH,
        )) fixture { f ->
            f.approveOnOriginalTv()
            val phone = f.candidate(f.startServer(mode))
            assertThat(phone.completeSyntheticExchange()).isFalse()
            assertThat(f.revokes.get()).isEqualTo(1)
        }
    }

    @Test fun `default route loss after TV confirmation cancels encrypted exchange`() =
        fixture { f ->
            f.approveOnOriginalTv()
            val phone = f.candidate(
                f.startServer(TvBehavior.REVOKE_ROUTE_AFTER_TV_CONFIRM),
            )
            assertThat(phone.completeSyntheticExchange()).isFalse()
            assertThat(f.allowed.get()).isFalse()
            assertThat(f.revokes.get()).isEqualTo(1)
        }

    @Test fun `LAN permission denied before binder sends no offer`() = fixture { f ->
        f.approveOnOriginalTv()
        f.allowed.set(false)
        val phone = f.candidate(f.startServer())
        assertThat(phone.completeSyntheticExchange()).isFalse()
        assertThat(f.binds.get()).isEqualTo(0)
        assertThat(f.destinationCalls.get()).isEqualTo(0)
        assertThat(f.revokes.get()).isEqualTo(1)
    }

    @Test fun `binder closes socket before connect and no fallback is attempted`() =
        fixture { f ->
            f.approveOnOriginalTv()
            val phone = f.candidate(f.startServer(), fakeBinder = {
                f.binds.incrementAndGet()
                it.close()
            })
            assertThat(phone.completeSyntheticExchange()).isFalse()
            assertThat(f.binds.get()).isEqualTo(1)
            assertThat(f.destinationCalls.get()).isEqualTo(0)
            assertThat(f.revokes.get()).isEqualTo(1)
        }

    @Test fun `owner onStop during blocked TV confirmation aborts without reconnect`() =
        fixture { f ->
            f.approveOnOriginalTv()
            val phone = f.candidate(f.startServer(TvBehavior.HOLD_BEFORE_TV_CONFIRM))
            val completed = AtomicReference<Boolean>()
            val thread = Thread {
                completed.set(phone.completeSyntheticExchange())
            }.apply { isDaemon = true; start() }
            try {
                assertThat(f.offerReceived.await(3, TimeUnit.SECONDS)).isTrue()
                phone.close()
                f.releaseTvConfirm.countDown()
                thread.join(3_500)
                assertThat(thread.isAlive).isFalse()
                assertThat(completed.get()).isFalse()
                assertThat(f.revokes.get()).isEqualTo(1)
            } finally {
                f.releaseTvConfirm.countDown()
                phone.close()
                thread.join(3_500)
            }
        }

    @Test fun `lease expiry before connection prevents any signed frame`() = fixture { f ->
        f.approveOnOriginalTv()
        val phone = f.candidate(f.startServer())
        f.now.addAndGet(120_000)
        assertThat(phone.completeSyntheticExchange()).isFalse()
        assertThat(f.destinationCalls.get()).isEqualTo(0)
        assertThat(f.revokes.get()).isEqualTo(1)
    }

    @Test fun `route loss while binding prevents even the first connection`() = fixture { f ->
        f.approveOnOriginalTv()
        val phone = f.candidate(f.startServer(), fakeBinder = {
            f.binds.incrementAndGet()
            f.allowed.set(false)
        })
        assertThat(phone.completeSyntheticExchange()).isFalse()
        assertThat(f.destinationCalls.get()).isEqualTo(0)
        assertThat(f.revokes.get()).isEqualTo(1)
    }
}

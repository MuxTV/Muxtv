package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
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

/** Genuine original-TV F1..F6 on TCP, then a SECOND actual APPDATA TCP socket. */
class Lc04SourceFirstRequestTcpTest {
    private class Fixture : AutoCloseable {
        val clock = AtomicLong(10_000L)
        val route = AtomicBoolean(true)
        val bindCount = AtomicInteger()
        val phoneRevocations = AtomicInteger()
        val original = TvHpkeQrSession.create(clock.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(
            original.presentation(clock.get()),
        ).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock::get)
        val gate = checkNotNull(original.phoneEnrollmentGate(clock::get))
        var handshakeTv: Lc04MxH1SealedPrivateLanTv? = null
        var handshakePhone: Lc04MxH1LeasedSealedPhone? = null
        var appTv: Lc04SourceFirstRequestTv? = null
        var appPhone: Lc04SourceFirstRequestPhone? = null

        fun approve() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val enrolled = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(original.activateVerifiedPhone(enrolled.identity, clock.get())).isTrue()
        }

        private fun loopback(): InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        fun finishRealSixFrames(): Pair<Lc04SourceAeadPort, Lc04SourceAeadPort> {
            approve()
            val server = Lc04MxH1SealedPrivateLanTv
                .startLoopbackWithSourceKeyHandoffForHostTests(
                    original, clock::get, route::get,
                ).also { handshakeTv = it }
            val client = Lc04MxH1LeasedSealedPhone.withTestSocketForHostileJVM(
                privateIpv4 = "192.168.52.17",
                port = server.port,
                originalPhone = phone,
                elapsedMillis = clock::get,
                selectedRouteEligible = route::get,
                bindUnconnectedToSelectedNetwork = { peer ->
                    assertThat(peer.isConnected).isFalse()
                },
                revokeOriginalQrAndKeys = { phoneRevocations.incrementAndGet() },
                testSocketSupplier = {
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            val source = endpoint as InetSocketAddress
                            if (source.address.hostAddress != "192.168.52.17" ||
                                source.port != server.port
                            ) throw IOException("Unapproved handshake endpoint.")
                            super.connect(InetSocketAddress(loopback(), server.port), timeout)
                        }
                    }
                },
            ).also { handshakePhone = it }
            val phoneRecords = checkNotNull(client.completeSourceKeyHandoffAfterTvEof())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!server.hasAuthenticatedSyntheticExchange() &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertThat(server.hasAuthenticatedSyntheticExchange()).isTrue()
            val tvRecords = checkNotNull(server.takeSourceKeyPortAfterRealF6OutputClose())
            assertThat(server.takeSourceKeyPortAfterRealF6OutputClose()).isNull()
            return phoneRecords to tvRecords
        }

        fun startAppTv(tvRecords: Lc04SourceAeadPort): Lc04SourceFirstRequestTv =
            Lc04SourceFirstRequestTv.startLoopbackForHostTests(
                original, tvRecords, clock::get, route::get,
            ).also { appTv = it }

        fun appClient(
            server: Lc04SourceFirstRequestTv,
            phoneRecords: Lc04SourceAeadPort,
        ): Lc04SourceFirstRequestPhone =
            Lc04SourceFirstRequestPhone.withTestSocketForHostOnly(
                ipLiteral = "192.168.52.17",
                port = server.port,
                sourceRecordsAfterRealTvEof = phoneRecords,
                selectedRouteEligible = route::get,
                bindUnconnectedToSelectedNetwork = { socket ->
                    assertThat(socket.isConnected).isFalse()
                    bindCount.incrementAndGet()
                },
                revokeOriginalPhoneOwner = { handshakePhone?.close() },
                testSocketFactory = {
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            val target = endpoint as InetSocketAddress
                            if (target.address.hostAddress != "192.168.52.17" ||
                                target.port != server.port
                            ) throw IOException("Unapproved APPDATA endpoint.")
                            super.connect(InetSocketAddress(loopback(), server.port), timeout)
                        }
                    }
                },
            ).also { appPhone = it }

        fun awaitAppProof(server: Lc04SourceFirstRequestTv) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!server.hasAuthenticatedFirstRequest() &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertThat(server.hasAuthenticatedFirstRequest()).isTrue()
        }

        fun awaitTvRevoke() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (original.presentation(clock.get()) != null &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertThat(original.presentation(clock.get())).isNull()
        }

        fun rawClient(port: Int): Socket = Socket(loopback(), port).apply {
            soTimeout = 3_000
        }

        override fun close() {
            appPhone?.close()
            appTv?.close()
            handshakePhone?.close()
            handshakeTv?.close()
            phone.close()
            signer.close()
            original.close()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun originalTvQrWithTwoRealTcpConnectionsAcceptsAuthenticatedFirstAppRequest() =
        fixture { f ->
            val (phonePort, tvPort) = f.finishRealSixFrames()
            val appTv = f.startAppTv(tvPort)
            val appPhone = f.appClient(appTv, phonePort)
            assertThat(appPhone.proveAuthenticatedFirstRequest()).isTrue()
            f.awaitAppProof(appTv)
            f.awaitTvRevoke() // success consumes the original TV QR as well
            assertThat(f.bindCount.get()).isEqualTo(1)
            assertThat(f.phoneRevocations.get()).isEqualTo(1)
            assertThat(appPhone.proveAuthenticatedFirstRequest()).isFalse()
        }

    @Test fun noAppTcpListenerBeforeRealSixthAndOriginalTvApproval() = fixture { f ->
        // Neither original QR alone nor an arbitrary port can open APPDATA.
        val fake = object : Lc04SourceAeadPort {
            override fun sealRequest(command: Lc04SourceCommand): ByteArray? = null
            override fun openRequest(wire: ByteArray): Lc04SourceCommand? = null
            override fun sealReply(
                reply: app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply,
            ): ByteArray? = null
            override fun openReply(
                wire: ByteArray,
            ): app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply? = null
            override fun close() {}
        }
        assertThat(runCatching {
            Lc04SourceFirstRequestTv.startLoopbackForHostTests(
                f.original, fake, f.clock::get,
            )
        }.isFailure).isTrue()
    }

    @Test fun forgedFirstAppdataFrameRevokesOriginalTvQrWithoutReply() =
        fixture { f ->
            val (_, tvPort) = f.finishRealSixFrames()
            val appTv = f.startAppTv(tvPort)
            f.rawClient(appTv.port).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(94)
                output.write(ByteArray(94) { 0x55.toByte() })
                output.flush()
                socket.shutdownOutput()
                try { assertThat(socket.getInputStream().read()).isEqualTo(-1) }
                catch (_: IOException) { /* TCP reset is terminal denial. */ }
            }
            f.awaitTvRevoke()
            assertThat(appTv.hasAuthenticatedFirstRequest()).isFalse()
        }

    @Test fun validEncryptedFirstRequestWithTrailingByteIsRejectedBeforeReply() =
        fixture { f ->
            val (phonePort, tvPort) = f.finishRealSixFrames()
            val appTv = f.startAppTv(tvPort)
            val real = checkNotNull(phonePort.sealRequest(Lc04SourceCommand.Cancel(1)))
            try {
                f.rawClient(appTv.port).use { socket ->
                    val output = DataOutputStream(socket.getOutputStream())
                    Lc04SourceFirstFrameIo.write(output, real)
                    output.writeByte(127) // no pipelining before source EOF
                    output.flush()
                    socket.shutdownOutput()
                    try { assertThat(socket.getInputStream().read()).isEqualTo(-1) }
                    catch (_: IOException) { /* Terminal RST is acceptable. */ }
                }
                f.awaitTvRevoke()
                assertThat(appTv.hasAuthenticatedFirstRequest()).isFalse()
            } finally { real.fill(0) }
        }

    @Test fun authenticPrepareM3uCannotCrossFirstRequestOnlySourceGate() =
        fixture { f ->
            val (phonePort, tvPort) = f.finishRealSixFrames()
            val appTv = f.startAppTv(tvPort)
            val wire = checkNotNull(phonePort.sealRequest(
                Lc04SourceCommand.PrepareM3u(1, "https://example.invalid/demo.m3u"),
            ))
            try {
                f.rawClient(appTv.port).use { socket ->
                    Lc04SourceFirstFrameIo.write(
                        DataOutputStream(socket.getOutputStream()), wire,
                    )
                    socket.shutdownOutput()
                    try { assertThat(socket.getInputStream().read()).isEqualTo(-1) }
                    catch (_: IOException) {}
                }
                f.awaitTvRevoke()
                assertThat(appTv.hasAuthenticatedFirstRequest()).isFalse()
            } finally { wire.fill(0) }
        }

    @Test fun sourceRouteRevokedBeforeConnectNeverBindsPhoneSocket() = fixture { f ->
        val (phonePort, tvPort) = f.finishRealSixFrames()
        val appTv = f.startAppTv(tvPort)
        val phone = f.appClient(appTv, phonePort)
        f.route.set(false)
        assertThat(phone.proveAuthenticatedFirstRequest()).isFalse()
        assertThat(f.bindCount.get()).isEqualTo(0)
        assertThat(f.phoneRevocations.get()).isEqualTo(1)
    }

    @Test fun oversizedFirstFrameFailsClosedBeforeAllocation() = fixture { f ->
        val (_, tvPort) = f.finishRealSixFrames()
        val appTv = f.startAppTv(tvPort)
        f.rawClient(appTv.port).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(500_000)
                flush()
            }
            socket.shutdownOutput()
        }
        f.awaitTvRevoke()
        assertThat(appTv.hasAuthenticatedFirstRequest()).isFalse()
    }

    @Test fun tvOnStopClosesPartialFirstAppRequestAndOriginalKeys() = fixture { f ->
        val (_, tvPort) = f.finishRealSixFrames()
        val appTv = f.startAppTv(tvPort)
        f.rawClient(appTv.port).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(94)
                writeByte(0x4d) // only one byte of an AES-GCM frame
                flush()
            }
            appTv.close()
            try { assertThat(socket.getInputStream().read()).isEqualTo(-1) }
            catch (_: IOException) {}
            assertThat(f.original.presentation(f.clock.get())).isNull()
            assertThat(appTv.hasAuthenticatedFirstRequest()).isFalse()
        }
    }

    @Test fun wrongOriginalQrSessionCannotAuthenticateFirstAppTcpRequest() {
        val a = Fixture()
        val b = Fixture()
        try {
            val (phoneA, _) = a.finishRealSixFrames()
            val (_, tvB) = b.finishRealSixFrames()
            val appB = b.startAppTv(tvB)
            val phoneAClient = a.appClient(appB, phoneA)
            assertThat(phoneAClient.proveAuthenticatedFirstRequest()).isFalse()
            b.awaitTvRevoke()
            assertThat(appB.hasAuthenticatedFirstRequest()).isFalse()
        } finally {
            a.close()
            b.close()
        }
    }
}

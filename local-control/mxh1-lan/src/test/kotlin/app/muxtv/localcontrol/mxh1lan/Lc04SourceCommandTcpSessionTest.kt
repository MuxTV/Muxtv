package app.muxtv.localcontrol.mxh1lan

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.onboarding.ProtectedSourceStaging
import app.muxtv.localcontrol.onboarding.SourceStagingResult
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
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

/** Real original-TV F1..F6 + real TV EOF, then real multi-command APPDATA TCP. */
class Lc04SourceCommandTcpSessionTest {
    private class Handle : SourcePreparationHandle()

    private class FakeOnboarding : SourceOnboarding {
        private val handle = Handle()
        val prepares = AtomicInteger()
        val activates = AtomicInteger()
        val cancels = AtomicInteger()
        @Volatile var lastRequest: SourcePreparationRequest? = null
        @Volatile var lastName: String? = null

        override suspend fun prepare(
            locator: String, insecureHttpApproved: Boolean,
        ): SourcePreparationResult = prepare(
            SourcePreparationRequest.M3u(locator, insecureHttpApproved),
        )

        override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
            prepares.incrementAndGet()
            lastRequest = request
            return SourcePreparationResult.Prepared(handle, "https://provider.invalid")
        }

        override suspend fun activate(
            handle: SourcePreparationHandle, sourceName: String,
        ): SourceActivationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            activates.incrementAndGet()
            lastName = sourceName
            return SourceActivationResult.Activated
        }

        override suspend fun cancel(
            handle: SourcePreparationHandle,
        ): SourceCancellationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            cancels.incrementAndGet()
            return SourceCancellationResult.Removed
        }

        override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
    }

    private class Fixture : AutoCloseable {
        val clock = AtomicLong(10_000L)
        val route = AtomicBoolean(true)
        val visible = AtomicBoolean(true)
        val bindCalls = AtomicInteger()
        val phoneOwnerClosed = AtomicInteger()
        val backend = FakeOnboarding()
        val staging = ProtectedSourceStaging(backend)
        val original = TvHpkeQrSession.create(clock.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(
            original.presentation(clock.get()),
        ).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock::get)
        val gate = checkNotNull(original.phoneEnrollmentGate(clock::get))
        var handshakeTv: Lc04MxH1SealedPrivateLanTv? = null
        var handshakePhone: Lc04MxH1LeasedSealedPhone? = null
        var appTv: Lc04SourceCommandTcpTv? = null
        var appPhone: Lc04SourceCommandTcpPhone? = null
        var phoneRecords: Lc04SourceAeadPort? = null

        private fun loopback() = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        fun approveAndRealF6(): Lc04SourceAeadPort {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val enrolled = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(original.activateVerifiedPhone(enrolled.identity, clock.get())).isTrue()
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
                bindUnconnectedToSelectedNetwork = {
                    assertThat(it.isConnected).isFalse()
                },
                revokeOriginalQrAndKeys = { phoneOwnerClosed.incrementAndGet() },
                testSocketSupplier = {
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            val target = endpoint as InetSocketAddress
                            if (target.address.hostAddress != "192.168.52.17" ||
                                target.port != server.port
                            ) throw IOException("Unapproved original handshake endpoint")
                            super.connect(InetSocketAddress(loopback(), server.port), timeout)
                        }
                    }
                },
            ).also { handshakePhone = it }
            val port = checkNotNull(client.completeSourceKeyHandoffAfterTvEof())
            phoneRecords = port
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!server.hasAuthenticatedSyntheticExchange() &&
                System.nanoTime() < until
            ) Thread.sleep(10)
            assertThat(server.hasAuthenticatedSyntheticExchange()).isTrue()
            return port
        }

        fun startTv(): Lc04SourceCommandTcpTv =
            Lc04SourceCommandTcpTv.startLoopbackAfterCompletedF6ForHostTests(
                completedHandshake = checkNotNull(handshakeTv),
                originalTvQrOwner = original,
                staging = staging,
                now = clock::get,
                selectedRouteEligible = route::get,
                isTvVisible = visible::get,
            ).also { appTv = it }

        fun startPhone(
            tv: Lc04SourceCommandTcpTv,
            records: Lc04SourceAeadPort = checkNotNull(phoneRecords),
        ): Lc04SourceCommandTcpPhone =
            Lc04SourceCommandTcpPhone.withTestSocketForHostOnly(
                address = "192.168.52.17",
                appdataPort = tv.port,
                sourceRecordsAfterRealTvEof = records,
                selectedRouteEligible = route::get,
                bindUnconnectedToSelectedNetwork = {
                    assertThat(it.isConnected).isFalse()
                    bindCalls.incrementAndGet()
                },
                revokeOriginalPhoneOwner = { handshakePhone?.close() },
                testSocketFactory = {
                    object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            val target = endpoint as InetSocketAddress
                            if (target.address.hostAddress != "192.168.52.17" ||
                                target.port != tv.port
                            ) throw IOException("Unapproved source APPDATA endpoint")
                            super.connect(InetSocketAddress(loopback(), tv.port), timeout)
                        }
                    }
                },
            ).also { appPhone = it }

        fun directTcp(tv: Lc04SourceCommandTcpTv): Socket =
            Socket(loopback(), tv.port).apply { soTimeout = 3_000 }

        fun awaitCleanup(tv: Lc04SourceCommandTcpTv) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (tv.cleanupStatusAfterStop() == null &&
                System.nanoTime() < until
            ) Thread.sleep(10)
            assertThat(tv.cleanupStatusAfterStop()).isNotNull()
        }

        override fun close() {
            appPhone?.close()
            appTv?.close()
            handshakePhone?.close()
            handshakeTv?.close()
            phoneRecords?.close()
            phone.close()
            signer.close()
            original.close()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun originalF6AndSecondTcpM3uPrepareTvDpadActivate() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        val client = f.startPhone(tv)
        val locator = "https://iptv.invalid/?token=PRIVATE"
        assertThat(client.prepareM3u(locator)).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat((f.backend.lastRequest as SourcePreparationRequest.M3u).locator)
            .isEqualTo(locator)
        assertThat(client.requestActivate("Каналы"))
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.backend.activates.get()).isEqualTo(0)
        assertThat(tv.pendingActivationNameForTv()).isEqualTo("Каналы")
        assertThat(tv.approvePendingActivationOnTv()).isTrue()
        assertThat(tv.approvePendingActivationOnTv()).isFalse()
        assertThat(client.requestActivate("Каналы"))
            .isEqualTo(Lc04SourceReplyCode.ACTIVATED)
        assertThat(f.backend.lastName).isEqualTo("Каналы")
        assertThat(f.backend.prepares.get()).isEqualTo(1)
        assertThat(f.backend.activates.get()).isEqualTo(1)
        assertThat(f.bindCalls.get()).isEqualTo(1) // one APPDATA TCP socket
        client.close()
        f.awaitCleanup(tv)
        assertThat(f.original.presentation(f.clock.get())).isNull()
    }

    @Test fun originalF6AndSecondTcpXtreamPasswordSecretSafeCancel() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        val phone = f.startPhone(tv)
        val status = phone.prepareXtream(
            "https://xtream.invalid", "UID_PRIVATE", "PWD_PRIVATE",
        )
        assertThat(status).isEqualTo(Lc04SourceReplyCode.PREPARED)
        val request = f.backend.lastRequest as SourcePreparationRequest.Xtream
        assertThat(request.username).isEqualTo("UID_PRIVATE")
        assertThat(request.password).isEqualTo("PWD_PRIVATE")
        assertThat(request.insecureHttpApproved).isFalse()
        assertThat(phone.toString()).doesNotContain("PWD_PRIVATE")
        assertThat(tv.toString()).doesNotContain("UID_PRIVATE")
        assertThat(phone.cancel()).isEqualTo(Lc04SourceReplyCode.CANCELED)
        assertThat(f.backend.cancels.get()).isEqualTo(1)
        phone.close()
        f.awaitCleanup(tv)
    }

    @Test fun forgedAppdataRecordOnSecondTcpNeverCallsOnboarding() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        f.directTcp(tv).use { socket ->
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(94)
            output.write(ByteArray(94) { 0x41.toByte() })
            output.flush()
            socket.shutdownOutput()
            try { assertThat(socket.getInputStream().read()).isEqualTo(-1) }
            catch (_: IOException) { /* TCP reset is terminal. */ }
        }
        f.awaitCleanup(tv)
        assertThat(f.backend.prepares.get()).isEqualTo(0)
        assertThat(f.original.presentation(f.clock.get())).isNull()
    }

    @Test fun replayedValidCiphertextNeverPreparesTwice() = fixture { f ->
        val records = f.approveAndRealF6()
        val tv = f.startTv()
        val first = checkNotNull(records.sealRequest(
            Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"),
        ))
        try {
            f.directTcp(tv).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                Lc04SourceFirstFrameIo.write(output, first)
                val reply = Lc04SourceFirstFrameIo.read(input)
                try {
                    assertThat(records.openReply(reply)?.code)
                        .isEqualTo(Lc04SourceReplyCode.PREPARED)
                } finally { reply.fill(0) }
                Lc04SourceFirstFrameIo.write(output, first)
                try { assertThat(input.read()).isEqualTo(-1) }
                catch (_: IOException) {}
            }
            f.awaitCleanup(tv)
            assertThat(f.backend.prepares.get()).isEqualTo(1)
            assertThat(f.backend.cancels.get()).isEqualTo(1)
        } finally { first.fill(0) }
    }

    @Test fun phoneRouteLossOnStopReleasesPreparedSource() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        val phone = f.startPhone(tv)
        assertThat(phone.prepareM3u("https://iptv.invalid"))
            .isEqualTo(Lc04SourceReplyCode.PREPARED)
        f.route.set(false)
        phone.close()
        tv.close()
        assertThat(phone.requestActivate("Must fail")).isNull()
        f.awaitCleanup(tv)
        assertThat(f.backend.activates.get()).isEqualTo(0)
        assertThat(f.backend.cancels.get()).isEqualTo(1)
    }

    @Test fun tvInvisibleBeforeDpadApprovalRevokesAllSourceAuthority() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        val phone = f.startPhone(tv)
        assertThat(phone.prepareM3u("https://iptv.invalid"))
            .isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(phone.requestActivate("Channels"))
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        f.visible.set(false)
        assertThat(tv.approvePendingActivationOnTv()).isFalse()
        tv.close()
        f.awaitCleanup(tv)
        assertThat(f.backend.activates.get()).isEqualTo(0)
        assertThat(f.backend.cancels.get()).isEqualTo(1)
    }

    @Test fun oversizedAppdataFrameIsTerminalBeforeAllocation() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        f.directTcp(tv).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(50_000)
                flush()
            }
            socket.shutdownOutput()
        }
        f.awaitCleanup(tv)
        assertThat(f.backend.prepares.get()).isEqualTo(0)
    }

    @Test fun sourceCommandSessionRejectsSeventeenthRequest() = fixture { f ->
        f.approveAndRealF6()
        val tv = f.startTv()
        val phone = f.startPhone(tv)
        repeat(16) { i ->
            assertThat(phone.cancel()).isEqualTo(Lc04SourceReplyCode.EMPTY)
        }
        assertThat(phone.cancel()).isNull()
        f.awaitCleanup(tv)
        assertThat(f.backend.prepares.get()).isEqualTo(0)
        assertThat(f.backend.activates.get()).isEqualTo(0)
        assertThat(f.bindCalls.get()).isEqualTo(1)
    }

}

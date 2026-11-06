package app.muxtv.localcontrol.mxh1tcp

import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04HandoffFrame
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

/** Actual loopback TCP streams, actual original TV QR and Tink/Ed25519/HPKE. */
class Lc04MxH1LoopbackTcpLabTest {
    private class Fixture : AutoCloseable {
        val now = AtomicLong(10_000)
        val clock = now::get
        val tv = TvHpkeQrSession.create(now.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(now.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, clock)
        val gate = checkNotNull(tv.phoneEnrollmentGate(clock))
        var server: Lc04MxH1LoopbackTv? = null
        var client: Lc04MxH1LoopbackPhone? = null

        fun approve() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            val signedProof = signer.prove(qr, challenge)
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signedProof) as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, now.get())).isTrue()
        }

        fun open(): Lc04MxH1LoopbackTv = checkNotNull(
            Lc04MxH1LoopbackTv.startLoopbackForTests(tv, clock).also { server = it },
        )
        fun phoneConnector(port: Int): Lc04MxH1LoopbackPhone =
            Lc04MxH1LoopbackPhone.loopbackForTests(port).also { client = it }

        override fun close() {
            client?.close()
            server?.close()
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun withFixture(f: (Fixture) -> Unit) {
        val current = Fixture()
        try { f(current) } finally { current.close() }
    }

    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        if (!predicate()) throw AssertionError(description)
    }

    private fun rawSend(port: Int, length: Int, payload: ByteArray) {
        Socket(loopback(), port).use { socket ->
            socket.soTimeout = 3_000
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(length)
            out.write(payload)
            out.flush()
            socket.shutdownOutput()
            try {
                assertThat(DataInputStream(socket.getInputStream()).read()).isEqualTo(-1)
            } catch (_: IOException) {
                // Reset is also a fail-closed denial; no success frame.
            }
        }
    }

    @Test fun oneRealSocketCompletesThreeFramesAndOriginalQrOwnership() =
        withFixture { f ->
            f.approve()
            val originalQr = checkNotNull(f.tv.presentation(f.now.get())).qrValue
            val server = f.open()
            val client = f.phoneConnector(server.port)
            assertThat(client.completeSyntheticExchange(f.phone)).isTrue()
            assertThat(server.hasSyntheticNetworkProof()).isTrue()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.now.get())).isTrue()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isTrue()
            assertThat(checkNotNull(f.tv.presentation(f.now.get())).qrValue)
                .isEqualTo(originalQr)
            assertThat(client.completeSyntheticExchange(f.phone)).isFalse()
            // Explicit Activity shutdown still revokes the EXACT same owner.
            server.close()
            assertThat(f.tv.presentation(f.now.get())).isNull()
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
        }

    @Test fun offerWithoutTvLocalApprovalRevokesOriginalOwner() =
        withFixture { f ->
            val server = f.open()
            assertThat(f.phoneConnector(server.port).completeSyntheticExchange(f.phone))
                .isFalse()
            awaitCondition("Unapproved TV owner revoked") {
                f.tv.presentation(f.now.get()) == null
            }
        }

    @Test fun legacyMxw1FramesAreRejectedWithoutAutomaticDowngrade() =
        withFixture { f ->
            f.approve()
            val server = f.open()
            fun unhex(s: String) = ByteArray(s.length / 2) {
                ((s[it * 2].digitToInt(16) shl 4) or
                    s[it * 2 + 1].digitToInt(16)).toByte()
            }
            val old = Lc04WireLab.encode(Lc04WireFrame(
                Lc04WireKind.PHONE_PUBLIC_KEY,
                unhex(f.qr.invitationId), unhex(f.qr.pairingId),
                unhex(f.qr.fingerprint), unhex(f.signer.fingerprint),
                byteArrayOf(7),
            ))
            rawSend(server.port, old.size, old)
            awaitCondition("MXW1 rejected and QR revoked") {
                f.tv.presentation(f.now.get()) == null
            }
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
        }

    @Test fun zeroNegativeOversizedAndIncompleteLengthPrefixesAllFailClosed() {
        for (length in listOf(-1, 0, 1, Lc04HandoffWireLab.MAX_FRAME_BYTES + 1)) {
            withFixture { f ->
                f.approve()
                val server = f.open()
                rawSend(server.port, length, byteArrayOf())
                awaitCondition("Rejected invalid length ${length}") {
                    f.tv.presentation(f.now.get()) == null
                }
            }
        }
        withFixture { f ->
            f.approve()
            val server = f.open()
            rawSend(server.port, Lc04HandoffWireLab.HEADER_BYTES + 100,
                byteArrayOf(0x4d, 0x58, 0x48, 0x31))
            awaitCondition("Rejected truncated frame") {
                f.tv.presentation(f.now.get()) == null
            }
        }
    }

    @Test fun forgedTvConfirmationCannotProducePhoneProofEvenWithMatchingDigest() =
        withFixture { f ->
            f.approve()
            val listener = ServerSocket(0, 1, loopback())
            val accepted = AtomicReference(false)
            val finished = CountDownLatch(1)
            val worker = Thread({
                try {
                    listener.use { server ->
                        server.accept().use { socket ->
                            socket.soTimeout = 2_500
                            val input = DataInputStream(socket.getInputStream())
                            val output = DataOutputStream(socket.getOutputStream())
                            val offer = Lc04MxH1LengthFrame.read(
                                input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                            )
                            val frame = checkNotNull(Lc04HandoffWireLab.decode(offer))
                            val digest = checkNotNull(Lc04HandoffWireLab.digestOfSignedOffer(offer))
                            val forged = Lc04HandoffWireLab.encode(Lc04HandoffFrame(
                                Lc04HandoffKind.TV_KEY_CONFIRM,
                                frame.invitation(), frame.pairing(), frame.tv(), frame.phone(),
                                digest, frame.counter,
                                Lc04HandoffWireLab.tvConfirmationBody(
                                    ByteArray(32) { 3 }, ByteArray(32) { 9 },
                                ),
                            ))
                            Lc04MxH1LengthFrame.write(
                                output, forged, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                            accepted.set(input.read() == -1) // no phone MAC was sent
                            offer.fill(0); digest.fill(0); forged.fill(0)
                        }
                    }
                } catch (_: IOException) {
                } finally { finished.countDown() }
            }, "mxh1-forged-tv-peer").apply { isDaemon = true }
            worker.start()
            try {
                val client = f.phoneConnector(listener.localPort)
                assertThat(client.completeSyntheticExchange(f.phone)).isFalse()
                assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(accepted.get()).isTrue()
            } finally {
                listener.close()
                worker.join(2_000)
            }
        }

    @Test fun forgedPhoneMacAfterRealTvConfirmCannotReceiveTerminalAck() =
        withFixture { f ->
            f.approve()
            val server = f.open()
            Socket(loopback(), server.port).use { socket ->
                socket.soTimeout = 3_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val offer = checkNotNull(f.phone.createSignedSeedOffer())
                Lc04MxH1LengthFrame.write(output, offer,
                    Lc04HandoffKind.SIGNED_SEED_OFFER)
                val tvConfirm = Lc04MxH1LengthFrame.read(
                    input, Lc04HandoffKind.TV_KEY_CONFIRM,
                )
                val validPhone = checkNotNull(f.phone.acceptTvConfirmation(tvConfirm))
                val forged = validPhone.clone()
                forged[forged.lastIndex] = (forged.last().toInt() xor 1).toByte()
                Lc04MxH1LengthFrame.write(output, forged,
                    Lc04HandoffKind.PHONE_KEY_CONFIRM)
                socket.shutdownOutput()
                try {
                    assertThat(input.read()).isEqualTo(-1)
                } catch (_: IOException) { /* close with RST rejects as well */ }
                offer.fill(0); tvConfirm.fill(0); validPhone.fill(0); forged.fill(0)
            }
            awaitCondition("TV owner denies forged MAC") {
                f.tv.presentation(f.now.get()) == null
            }
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
        }

    @Test fun extraPipelinedBytesAfterThirdFrameRevokeEntireSession() =
        withFixture { f ->
            f.approve()
            val server = f.open()
            Socket(loopback(), server.port).use { socket ->
                socket.soTimeout = 3_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val offer = checkNotNull(f.phone.createSignedSeedOffer())
                Lc04MxH1LengthFrame.write(output, offer,
                    Lc04HandoffKind.SIGNED_SEED_OFFER)
                val tvConfirm = Lc04MxH1LengthFrame.read(input,
                    Lc04HandoffKind.TV_KEY_CONFIRM)
                val validPhone = checkNotNull(f.phone.acceptTvConfirmation(tvConfirm))
                Lc04MxH1LengthFrame.write(output, validPhone,
                    Lc04HandoffKind.PHONE_KEY_CONFIRM)
                output.writeByte(0x77) // smuggling a fourth frame
                output.flush()
                socket.shutdownOutput()
                try {
                    assertThat(input.read()).isEqualTo(-1)
                } catch (_: IOException) { /* reset is denial */ }
                offer.fill(0); tvConfirm.fill(0); validPhone.fill(0)
            }
            awaitCondition("Extra bytes revoke QR") {
                f.tv.presentation(f.now.get()) == null
            }
        }

    @Test fun tvLifecycleCloseInterruptsPeerWaitingOnPartialFirstFrame() =
        withFixture { f ->
            f.approve()
            val server = f.open()
            Socket(loopback(), server.port).use { socket ->
                socket.soTimeout = 3_000
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(500)
                output.writeByte(0x4d)
                output.flush()
                // TV Activity onStop synchronously closes the socket and owner
                // without waiting for a 2.5-second peer timeout.
                server.close()
                try {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1)
                } catch (_: IOException) { /* reset is denial */ }
            }
            assertThat(f.tv.presentation(f.now.get())).isNull()
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
        }

    @Test fun phoneLifecycleCloseInterruptsPeerThatNeverAnswers() =
        withFixture { f ->
            f.approve()
            val malicious = ServerSocket(0, 1, loopback())
            val accepted = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val thread = Thread({
                try {
                    malicious.use {
                        it.accept().use { socket ->
                            accepted.countDown()
                            socket.soTimeout = 3_000
                            // Deliberately never answer the phone's offer.
                            try { socket.getInputStream().read() }
                            catch (_: IOException) {}
                        }
                    }
                } catch (_: IOException) {
                } finally { finished.countDown() }
            }, "mxh1-silent-peer").apply { isDaemon = true }
            thread.start()
            val client = f.phoneConnector(malicious.localPort)
            val outcome = AtomicReference<Boolean?>(null)
            val caller = Thread({
                outcome.set(client.completeSyntheticExchange(f.phone))
            }, "mxh1-cancellable-phone").apply { isDaemon = true }
            caller.start()
            try {
                assertThat(accepted.await(3, TimeUnit.SECONDS)).isTrue()
                client.close()
                caller.join(2_500)
                assertThat(caller.isAlive).isFalse()
                assertThat(outcome.get()).isFalse()
                assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            } finally {
                malicious.close()
                client.close()
                thread.join(2_500)
            }
        }

    @Test fun expiryClosesListenerAndNeverAcceptsLateOffer() =
        withFixture { f ->
            f.approve()
            val server = f.open()
            f.now.addAndGet(TvHpkeQrSession.TTL_MILLIS)
            awaitCondition("Original TV QR expired") {
                f.tv.presentation(f.now.get()) == null
            }
            assertThat(server.hasSyntheticNetworkProof()).isFalse()
            assertThat(f.phoneConnector(server.port).completeSyntheticExchange(f.phone))
                .isFalse()
        }

    /**
     * A hostile loopback peer first performs the REAL original-TV-owned
     * Ed25519/HPKE/HKDF and both valid HMAC confirmations, then changes
     * ONLY the final TCP bytes. This isolates the final-message trust gate
     * from earlier signature/key-confirmation failures.
     */
    private fun injectHostileFinished(
        send: (DataOutputStream, ByteArray) -> Unit,
    ) = withFixture { f ->
        f.approve()
        val listener = ServerSocket(0, 1, loopback())
        val finished = CountDownLatch(1)
        val failureRef = AtomicReference<Throwable?>(null)
        val serverThread = Thread({
            try {
                listener.use {
                    it.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val offer = Lc04MxH1LengthFrame.read(
                            input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                        )
                        val tvReply = checkNotNull(
                            f.tv.acceptSyntheticMxH1SeedOffer(offer, f.now.get()),
                        )
                        Lc04MxH1LengthFrame.write(
                            output, tvReply, Lc04HandoffKind.TV_KEY_CONFIRM,
                        )
                        val phoneReply = Lc04MxH1LengthFrame.read(
                            input, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                        )
                        if (input.read() != -1) error("Client did not half-close")
                        val authenticFinished = checkNotNull(
                            f.tv.finishSyntheticMxH1PhoneConfirmation(
                                phoneReply, f.now.get(),
                            ),
                        )
                        try {
                            send(output, authenticFinished)
                            output.flush()
                            socket.shutdownOutput()
                        } finally {
                            offer.fill(0); tvReply.fill(0); phoneReply.fill(0)
                            authenticFinished.fill(0)
                        }
                    }
                }
            } catch (failure: Throwable) {
                failureRef.set(failure)
            } finally { finished.countDown() }
        }, "lc04-hostile-finished").apply { isDaemon = true }
        serverThread.start()
        try {
            val client = f.phoneConnector(listener.localPort)
            assertThat(client.completeSyntheticExchange(f.phone)).isFalse()
            assertThat(f.phone.hasAuthenticatedTvSyntheticProof()).isFalse()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(failureRef.get()).isNull()
        } finally {
            listener.close()
            serverThread.join(2_000)
        }
    }

    @Test fun forgedFinalTvHmacAfterValidRealPhoneProofNeverCompletes() =
        injectHostileFinished { out, authentic ->
            val forged = authentic.clone()
            forged[forged.lastIndex] = (forged.last().toInt() xor 1).toByte()
            out.writeInt(forged.size)
            out.write(forged)
            forged.fill(0)
        }

    @Test fun missingFinalFrameAfterRealPhoneProofNeverAuthenticates() =
        injectHostileFinished { _, _ ->
            // The TV accepted PHONE_CONFIRM, but the TCP peer never sends
            // final key confirmation. Phone must deny, not guess success.
        }

    @Test fun zeroLengthLegacyTransportMarkerNoLongerCountsAsFinalProof() =
        injectHostileFinished { out, _ ->
            out.writeInt(0)
        }

    @Test fun authenticatedFinishedWithTrailingSmuggledByteIsNotAccepted() =
        injectHostileFinished { out, authentic ->
            out.writeInt(authentic.size)
            out.write(authentic)
            out.writeByte(0x7f)
        }

    @Test fun authenticatedFinishedTruncatedBeforeLastMacByteIsNotAccepted() =
        injectHostileFinished { out, authentic ->
            out.writeInt(authentic.size)
            out.write(authentic, 0, authentic.size - 1)
        }

    @Test fun finalFrameWithWrongKindCounterOrOfferBindingFailsClosed() {
        for (offset in listOf(5, 6, 22, 38, 70, 102, 135)) {
            injectHostileFinished { out, authentic ->
                val changed = authentic.clone()
                changed[offset] = (changed[offset].toInt() xor 1).toByte()
                out.writeInt(changed.size)
                out.write(changed)
                changed.fill(0)
            }
        }
    }

    @Test fun noIpRouteOrCredentialApisAreExposedByTestOnlyTransport() =
        withFixture { f ->
            assertThat(f.tv.toString()).contains("[EPHEMERAL_KEY_REDACTED]")
            val server = f.open()
            assertThat(server.toString()).contains("[PRIVATE_SOCKET_REDACTED]")
            assertThat(f.phoneConnector(server.port).toString())
                .contains("[PRIVATE_SOCKET_REDACTED]")
        }
}

private fun loopback(): InetAddress =
    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

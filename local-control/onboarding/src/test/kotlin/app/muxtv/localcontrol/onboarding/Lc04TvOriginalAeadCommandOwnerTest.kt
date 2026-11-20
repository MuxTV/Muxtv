package app.muxtv.localcontrol.onboarding

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * TV source permission, ciphertext and existing catalog boundary using an
 * ORIGINAL approved TV QR, signed Ed25519 offer and HPKE F1–F6. The crypto
 * proof is in-process; the real two-socket F6/EOF integration remains
 * separately exercised by :local-control:mxh1-lan:test.
 */
class Lc04TvOriginalAeadCommandOwnerTest {
    private class Handle : SourcePreparationHandle()

    private class FakeOnboarding : SourceOnboarding {
        private val handle = Handle()
        var prepares = 0
        var activates = 0
        var cancels = 0
        var lastRequest: SourcePreparationRequest? = null
        var lastSourceName: String? = null
        var onActivate: (() -> Unit)? = null
        var nextPrepare: SourcePreparationResult =
            SourcePreparationResult.Prepared(handle, "https://iptv.invalid")
        override suspend fun prepare(
            locator: String, insecureHttpApproved: Boolean,
        ): SourcePreparationResult =
            prepare(SourcePreparationRequest.M3u(locator, insecureHttpApproved))

        override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
            prepares++
            lastRequest = request
            return nextPrepare
        }

        override suspend fun activate(
            handle: SourcePreparationHandle, sourceName: String,
        ): SourceActivationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            activates++
            lastSourceName = sourceName
            onActivate?.invoke()
            return SourceActivationResult.Activated
        }

        override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            cancels++
            return SourceCancellationResult.Removed
        }

        override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
    }

    private class Fixture : AutoCloseable {
        val time = AtomicLong(10_000)
        val route = AtomicBoolean(true)
        val visible = AtomicBoolean(true)
        val tv = TvHpkeQrSession.create(time.get())
        val qr = TvQrPublicKeyParser.parse(checkNotNull(tv.presentation(time.get())).qrValue)
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, time::get)
        val gate = checkNotNull(tv.phoneEnrollmentGate(time::get))
        val backend = FakeOnboarding()
        val staging = ProtectedSourceStaging(backend)
        var owner: Lc04TvOriginalAeadCommandOwner? = null
        var tvRecords: Lc04SourceAeadPort? = null
        var phoneRecords: Lc04SourceAeadPort? = null

        fun originalF6(): Pair<Lc04SourceAeadPort, Lc04SourceAeadPort> {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(verified.identity, time.get())).isTrue()

            val offer = checkNotNull(phone.createSignedSeedOffer())
            val second = checkNotNull(tv.acceptSyntheticMxH1SeedOffer(offer, time.get()))
            val third = checkNotNull(phone.acceptTvConfirmationAwaitingFinished(second))
            val fourth = checkNotNull(tv.finishSyntheticMxH1ForProbeLab(third, time::get))
            assertThat(phone.acceptTvFinishedForProbeLab(fourth, time::get)).isTrue()
            val fifth = checkNotNull(phone.sealMxH1SyntheticProbeForLab())
            assertThat(tv.acceptMxH1SyntheticProbeForLab(fifth, time.get())).isTrue()
            val sixth = checkNotNull(tv.sealMxH1SyntheticProbeForLab(time.get()))
            assertThat(phone.acceptMxH1SyntheticProbeForLab(sixth)).isTrue()
            val onTv = checkNotNull(tv.claimSourceRecordsAfterSixthWritten(
                time::get, route::get,
            )).also { tvRecords = it }
            val onPhone = checkNotNull(phone.claimSourceRecordsAfterVerifiedTvEof(
                time::get, route::get,
            )).also { phoneRecords = it }
            for (bytes in listOf(offer, second, third, fourth, fifth, sixth)) bytes.fill(0)
            return onPhone to onTv
        }

        fun open(): Lc04TvOriginalAeadCommandOwner {
            val (_, tvPort) = originalF6()
            return Lc04TvOriginalAeadCommandOwner.fromOriginalTvRecordsAfterRealF6(
                originalTv = tv,
                sourceRecords = tvPort,
                staging = staging,
                clock = time::get,
                selectedRouteEligible = route::get,
                isTvVisible = visible::get,
            ).also { owner = it }
        }

        suspend fun send(command: Lc04SourceCommand): Lc04SourceReply? {
            val port = checkNotNull(phoneRecords)
            val wire = port.sealRequest(command) ?: return null
            return try {
                val response = owner?.acceptAuthenticatedRecord(wire) ?: return null
                try { port.openReply(response) } finally { response.fill(0) }
            } finally { wire.fill(0) }
        }

        override fun close() {
            owner?.close()
            phoneRecords?.close()
            tvRecords?.close()
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun fixture(test: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { test(f) } finally { f.close() }
    }

    @Test fun originalTvF6M3uPrepareAndExactTvDpadApprovalActivate() = fixture { f ->
        f.open()
        val secret = "https://iptv.invalid/?token=PRIVATE_VALUE"
        val prepare = f.send(Lc04SourceCommand.PrepareM3u(1, secret))
        assertThat(prepare?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat((f.backend.lastRequest as SourcePreparationRequest.M3u).locator)
            .isEqualTo(secret)
        assertThat(prepare.toString()).doesNotContain("PRIVATE_VALUE")
        assertThat(f.owner?.pendingActivationNameForTv()).isNull()
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()

        assertThat(f.send(Lc04SourceCommand.Activate(2, "Каналы"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.backend.activates).isEqualTo(0)
        assertThat(f.owner?.pendingActivationNameForTv()).isEqualTo("Каналы")
        assertThat(f.owner?.approvePendingActivationOnTv()).isTrue()
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()

        assertThat(f.send(Lc04SourceCommand.Activate(3, "Каналы"))?.code)
            .isEqualTo(Lc04SourceReplyCode.ACTIVATED)
        assertThat(f.backend.lastSourceName).isEqualTo("Каналы")
        assertThat(f.backend.activates).isEqualTo(1)
        assertThat(f.send(Lc04SourceCommand.Activate(4, "Каналы"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.backend.activates).isEqualTo(1)
    }

    @Test fun xtreamPasswordIsAuthenticatedButNotEchoedToPhone() = fixture { f ->
        f.open()
        val username = "u-TOPSECRET"
        val password = "p-TOPSECRET"
        val secret = f.send(Lc04SourceCommand.PrepareXtream(
            1, "https://xtream.invalid", username, password,
        ))
        assertThat(secret?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        val request = f.backend.lastRequest as SourcePreparationRequest.Xtream
        assertThat(request.username).isEqualTo(username)
        assertThat(request.password).isEqualTo(password)
        assertThat(request.insecureHttpApproved).isFalse()
        assertThat(secret.toString()).doesNotContain("TOPSECRET")
        assertThat(f.send(Lc04SourceCommand.Cancel(2))?.code)
            .isEqualTo(Lc04SourceReplyCode.CANCELED)
        assertThat(f.backend.cancels).isEqualTo(1)
    }

    @Test fun diagnosticTvSuccessAndFakeAppPortCannotCreateSourcePrincipal() =
        fixture { f ->
            // Before original TV approval/F6, no source authority exists.
            val fake = object : Lc04SourceAeadPort {
                override fun sealRequest(command: Lc04SourceCommand): ByteArray? = null
                override fun openRequest(wire: ByteArray): Lc04SourceCommand? = null
                override fun sealReply(reply: Lc04SourceReply): ByteArray? = null
                override fun openReply(wire: ByteArray): Lc04SourceReply? = null
                override fun close() {}
            }
            val start = {
                Lc04TvOriginalAeadCommandOwner.fromOriginalTvRecordsAfterRealF6(
                    f.tv, fake, f.staging, f.time::get, f.route::get, f.visible::get,
                )
            }
            assertThat(runCatching(start).isFailure).isTrue()
            f.originalF6()
            assertThat(f.tv.hasSyntheticMxH1Proof(f.time.get())).isTrue()
            // Even a genuine handshake success Boolean cannot authorize a
            // forged/independently derived port: exact object identity required.
            assertThat(runCatching(start).isFailure).isTrue()
            assertThat(f.backend.prepares).isEqualTo(0)
        }

    @Test fun mismatchedTvOriginalOwnerCannotUseOtherSessionsAeadPort() =
        fixture { f ->
            val (_, fromF) = f.originalF6()
            val g = Fixture()
            try {
                val (_, fromG) = g.originalF6()
                assertThat(runCatching {
                    Lc04TvOriginalAeadCommandOwner.fromOriginalTvRecordsAfterRealF6(
                        g.tv, fromF, g.staging, g.time::get, g.route::get, g.visible::get,
                    )
                }.isFailure).isTrue()
                assertThat(g.tv.ownsOriginalSourceRecords(fromG, g.time.get())).isTrue()
                assertThat(f.tv.ownsOriginalSourceRecords(fromF, f.time.get())).isTrue()
            } finally { g.close() }
        }

    @Test fun staleApprovalAndDifferentSourceNameNeverActivate() = fixture { f ->
        f.open()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(
            1, "https://iptv.invalid",
        ))?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(f.send(Lc04SourceCommand.Activate(2, "A"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        f.visible.set(false)
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()
        f.visible.set(true)
        assertThat(f.owner?.approvePendingActivationOnTv()).isTrue()
        assertThat(f.send(Lc04SourceCommand.Activate(3, "B"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.backend.activates).isEqualTo(0)
        f.time.addAndGet(15_000)
        assertThat(f.owner?.approvePendingActivationOnTv()).isFalse()
        assertThat(f.send(Lc04SourceCommand.Activate(4, "B"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.backend.activates).isEqualTo(0)
    }

    @Test fun routeLossAfterPreparedSourceDeniesAndCleanupCancelsHandle() =
        fixture { f ->
            f.open()
            assertThat(f.send(Lc04SourceCommand.PrepareM3u(
                1, "https://iptv.invalid",
            ))?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
            f.route.set(false)
            assertThat(f.send(Lc04SourceCommand.Activate(2, "Channels"))).isNull()
            assertThat(f.backend.activates).isEqualTo(0)
            assertThat(f.owner?.discardPreparedAfterTvStop())
                .isEqualTo(SourceStagingResult.CANCELED)
            assertThat(f.backend.cancels).isEqualTo(1)
        }

    @Test fun replayedAeadSequenceNeverInvokesOnboardingTwice() = fixture { f ->
        f.open()
        val port = checkNotNull(f.phoneRecords)
        val wire = checkNotNull(port.sealRequest(Lc04SourceCommand.PrepareM3u(
            1, "https://iptv.invalid",
        )))
        try {
            val reply = checkNotNull(f.owner?.acceptAuthenticatedRecord(wire))
            assertThat(port.openReply(reply)?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
            reply.fill(0)
            assertThat(f.owner?.acceptAuthenticatedRecord(wire)).isNull()
            assertThat(f.backend.prepares).isEqualTo(1)
            assertThat(f.owner?.discardPreparedAfterTvStop())
                .isEqualTo(SourceStagingResult.CANCELED)
        } finally { wire.fill(0) }
    }

    @Test fun originalQrExpiryRejectsNextRealAeadRecordBeforeCatalogWrite() =
        fixture { f ->
            f.open()
            val first = checkNotNull(f.send(Lc04SourceCommand.Cancel(1)))
            assertThat(first.code).isEqualTo(Lc04SourceReplyCode.EMPTY)
            val second = checkNotNull(f.phoneRecords?.sealRequest(
                Lc04SourceCommand.PrepareM3u(2, "https://iptv.invalid"),
            ))
            try {
                f.time.addAndGet(TvHpkeQrSession.TTL_MILLIS)
                assertThat(f.owner?.acceptAuthenticatedRecord(second)).isNull()
                assertThat(f.backend.prepares).isEqualTo(0)
            } finally { second.fill(0) }
        }

    @Test fun onStopDuringDurableActivationNeverConfirmsSuccess() = fixture { f ->
        f.open()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(
            1, "https://iptv.invalid",
        ))?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(f.send(Lc04SourceCommand.Activate(2, "Channels"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.owner?.approvePendingActivationOnTv()).isTrue()
        f.backend.onActivate = { f.owner?.close() }
        assertThat(f.send(Lc04SourceCommand.Activate(3, "Channels"))).isNull()
        assertThat(f.backend.activates).isEqualTo(1)
        assertThat(f.owner?.discardPreparedAfterTvStop())
            .isEqualTo(SourceStagingResult.EMPTY)
        assertThat(f.send(Lc04SourceCommand.Activate(4, "Channels"))).isNull()
    }
}

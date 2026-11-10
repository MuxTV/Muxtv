package app.muxtv.localcontrol.onboarding

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.localcontrol.PairingAuthority
import app.muxtv.localcontrol.PairingClaim
import app.muxtv.localcontrol.PairingGrant
import app.muxtv.localcontrol.PairingRedemption
import app.muxtv.localcontrol.protectedchannel.Lc04SourceCommand
import app.muxtv.localcontrol.protectedchannel.Lc04SourceCommandCodec
import app.muxtv.localcontrol.protectedchannel.Lc04SourceReply
import app.muxtv.localcontrol.protectedchannel.Lc04SourceReplyCode
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class Lc04TvSourceCommandSessionTest {
    private class Handle : SourcePreparationHandle()
    private class FakeOnboarding : SourceOnboarding {
        val handle = Handle()
        var lastRequest: SourcePreparationRequest? = null
        var lastName: String? = null
        var prepares = 0
        var activates = 0
        var cancels = 0
        var afterActivate: (() -> Unit)? = null
        var canceled: SourceCancellationResult = SourceCancellationResult.Removed
        var prepared: SourcePreparationResult =
            SourcePreparationResult.Prepared(handle, "https://provider.invalid")
        var activated: SourceActivationResult = SourceActivationResult.Activated

        override suspend fun prepare(
            locator: String,
            insecureHttpApproved: Boolean,
        ): SourcePreparationResult =
            prepare(SourcePreparationRequest.M3u(locator, insecureHttpApproved))

        override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
            lastRequest = request
            prepares++
            return prepared
        }

        override suspend fun activate(
            handle: SourcePreparationHandle, sourceName: String,
        ): SourceActivationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            activates++
            lastName = sourceName
            afterActivate?.invoke()
            return activated
        }

        override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            cancels++
            return canceled
        }

        override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
    }

    private class Fixture(
        val port: FakeOnboarding = FakeOnboarding(),
        val clock: AtomicLong = AtomicLong(1_000),
    ) {
        val authority = PairingAuthority()
        val grant: PairingGrant
        val principal: ProtectedSourcePrincipal
        val staging = ProtectedSourceStaging(port)
        var approved = false
        val session: Lc04TvSourceCommandSession

        init {
            val oneTime = authority.begin().secret
            val pending = authority.claim(oneTime, "Phone")
                as PairingClaim.AwaitingTvConfirmation
            check(authority.approve(pending.claimId))
            grant = (authority.redeem(pending.claimId, oneTime)
                as PairingRedemption.Granted).grant
            principal = ProtectedSourcePrincipal(authority, grant)
            session = Lc04TvSourceCommandSession(
                principal = principal,
                staging = staging,
                clock = clock::get,
                consumeTvActivationApproval = {
                    if (approved) { approved = false; true } else false
                },
            )
        }

        suspend fun send(command: Lc04SourceCommand): Lc04SourceReply? {
            val plaintext = checkNotNull(Lc04SourceCommandCodec.encodeRequest(command))
            return Lc04SourceCommandCodec.decodeReply(
                session.acceptAuthenticatedPlaintext(plaintext) ?: return null,
            )
        }
    }

    @Test fun m3uPrepareAndTvLocalApprovalThenActivateUseExistingOnboarding() = runBlocking {
        val f = Fixture()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(
            1, "https://iptv.invalid/token=SECRET",
        ))?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(f.port.prepares).isEqualTo(1)
        assertThat((f.port.lastRequest as SourcePreparationRequest.M3u).locator)
            .isEqualTo("https://iptv.invalid/token=SECRET")
        assertThat(f.send(Lc04SourceCommand.Activate(2, "Каналы"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
        assertThat(f.port.activates).isEqualTo(0)
        f.approved = true // Simulates explicit TV-only D-pad grant.
        val result = checkNotNull(f.send(Lc04SourceCommand.Activate(3, "Каналы")))
        assertThat(result.code).isEqualTo(Lc04SourceReplyCode.ACTIVATED)
        assertThat(result.toString()).doesNotContain("SECRET")
        assertThat(f.port.lastName).isEqualTo("Каналы")
        assertThat(f.port.activates).isEqualTo(1)
        assertThat(f.send(Lc04SourceCommand.Activate(4, "Каналы"))?.code)
            .isEqualTo(Lc04SourceReplyCode.EMPTY)
    }

    @Test fun xtreamCredentialsReachOnlyExistingPortAndNeverReply() = runBlocking {
        val f = Fixture()
        val first = checkNotNull(f.send(Lc04SourceCommand.PrepareXtream(
            1, "https://xtream.invalid", "user-secret", "password-secret",
        )))
        assertThat(first.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        val request = f.port.lastRequest as SourcePreparationRequest.Xtream
        assertThat(request.username).isEqualTo("user-secret")
        assertThat(request.password).isEqualTo("password-secret")
        assertThat(first.toString()).doesNotContain("password-secret")
        assertThat(first.toString()).doesNotContain("xtream.invalid")
        assertThat(f.send(Lc04SourceCommand.PrepareXtream(
            2, "https://xtream.invalid", "different", "different",
        ))?.code).isEqualTo(Lc04SourceReplyCode.ALREADY_PREPARED)
        assertThat(f.port.prepares).isEqualTo(1)
        assertThat(f.send(Lc04SourceCommand.Cancel(3))?.code)
            .isEqualTo(Lc04SourceReplyCode.CANCELED)
        assertThat(f.port.cancels).isEqualTo(1)
    }

    @Test fun repeatedSequenceClosesSessionWithoutASecondSourceOperation() = runBlocking {
        val f = Fixture()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"))?.code)
            .isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(f.send(Lc04SourceCommand.Cancel(1))).isNull()
        assertThat(f.send(Lc04SourceCommand.Cancel(2))).isNull()
        assertThat(f.port.cancels).isEqualTo(0)
    }

    @Test fun outOfOrderAndMalformedPlaintextAreTerminal() = runBlocking {
        val f = Fixture()
        assertThat(f.send(Lc04SourceCommand.Cancel(3))).isNull()
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid")))
            .isNull()
        assertThat(f.port.prepares).isEqualTo(0)

        val g = Fixture()
        assertThat(g.session.acceptAuthenticatedPlaintext(byteArrayOf(1, 2, 3))).isNull()
        assertThat(g.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid")))
            .isNull()
        assertThat(g.port.prepares).isEqualTo(0)
    }

    @Test fun activationExceptionIsUnknownAndNeverReplayable() = runBlocking {
        val f = Fixture()
        f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"))
        f.port.afterActivate = { throw IllegalStateException("may have committed") }
        f.approved = true
        assertThat(f.send(Lc04SourceCommand.Activate(2, "Channels"))?.code)
            .isEqualTo(Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN)
        assertThat(f.port.activates).isEqualTo(1)
        assertThat(f.port.cancels).isEqualTo(0)
        assertThat(f.send(Lc04SourceCommand.Activate(3, "Channels"))).isNull()
        assertThat(f.staging.activate(f.principal, "Channels"))
            .isEqualTo(SourceStagingResult.EMPTY)
    }

    @Test fun activationCancelledAfterPotentialCommitCannotBeRetried() = runBlocking {
        val f = Fixture()
        f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"))
        f.port.afterActivate = { throw CancellationException("cancel after commit") }
        f.approved = true
        try {
            f.send(Lc04SourceCommand.Activate(2, "Channels"))
            throw AssertionError("Expected terminal cancellation")
        } catch (_: CancellationException) { /* Session must remain closed. */ }
        assertThat(f.port.activates).isEqualTo(1)
        assertThat(f.send(Lc04SourceCommand.Activate(3, "Channels"))).isNull()
        assertThat(f.staging.activate(f.principal, "Channels"))
            .isEqualTo(SourceStagingResult.EMPTY)
    }

    @Test fun concurrentTvRevocationDuringActivationReportsUnknown() = runBlocking {
        val f = Fixture()
        f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"))
        f.port.afterActivate = { f.authority.revoke(f.grant.sessionId) }
        f.approved = true
        assertThat(f.send(Lc04SourceCommand.Activate(2, "Channels"))?.code)
            .isEqualTo(Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN)
        assertThat(f.port.activates).isEqualTo(1)
        assertThat(f.send(Lc04SourceCommand.Cancel(3))).isNull()
    }

    @Test fun activityStopDuringActivationCannotReportKnownSuccess() = runBlocking {
        val f = Fixture()
        f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"))
        f.port.afterActivate = { f.session.close() }
        f.approved = true
        assertThat(f.send(Lc04SourceCommand.Activate(2, "Channels"))?.code)
            .isEqualTo(Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN)
        assertThat(f.send(Lc04SourceCommand.Cancel(3))).isNull()
    }

    @Test fun revokeThenCleanupRemovesPreparedHandleWithoutPhoneBearer() = runBlocking {
        val f = Fixture()
        f.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid"))
        assertThat(runCatching {
            runBlocking { f.session.discardAfterOwnerRevocation() }
        }.isFailure).isTrue()
        assertThat(f.authority.revoke(f.grant.sessionId)).isTrue()
        assertThat(f.session.discardAfterOwnerRevocation())
            .isEqualTo(SourceStagingResult.CANCELED)
        assertThat(f.port.cancels).isEqualTo(1)
        assertThat(f.send(Lc04SourceCommand.Activate(2, "Channels"))).isNull()
    }

    @Test fun rolledBackClockAndExpiryStopWithoutProviderCalls() = runBlocking {
        val f = Fixture()
        f.clock.set(1_200)
        assertThat(f.send(Lc04SourceCommand.Cancel(1))?.code)
            .isEqualTo(Lc04SourceReplyCode.EMPTY)
        f.clock.set(1_199)
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(2, "https://iptv.invalid")))
            .isNull()
        assertThat(f.port.prepares).isEqualTo(0)

        val g = Fixture()
        g.clock.set(121_000)
        assertThat(g.send(Lc04SourceCommand.PrepareM3u(1, "https://iptv.invalid")))
            .isNull()
        assertThat(g.port.prepares).isEqualTo(0)
    }

    @Test fun insecureHttpApprovalRemainsTvOwnedAndPhoneCannotSetFlag() = runBlocking {
        val f = Fixture()
        f.port.prepared = SourcePreparationResult.InsecureTransportApprovalRequired
        assertThat(f.send(Lc04SourceCommand.PrepareM3u(1, "http://iptv.invalid"))?.code)
            .isEqualTo(Lc04SourceReplyCode.TV_HTTP_APPROVAL_REQUIRED)
        val request = f.port.lastRequest as SourcePreparationRequest.M3u
        assertThat(request.insecureHttpApproved).isFalse()
        assertThat(f.send(Lc04SourceCommand.Cancel(2))?.code)
            .isEqualTo(Lc04SourceReplyCode.EMPTY)
    }
}

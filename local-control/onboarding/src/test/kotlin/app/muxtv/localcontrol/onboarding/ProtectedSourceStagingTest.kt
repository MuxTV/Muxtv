package app.muxtv.localcontrol.onboarding

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.localcontrol.PairingAuthority
import app.muxtv.localcontrol.PairingCapability
import app.muxtv.localcontrol.PairingClaim
import app.muxtv.localcontrol.PairingGrant
import app.muxtv.localcontrol.PairingRedemption
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProtectedSourceStagingTest {
    private class Handle : SourcePreparationHandle()

    private class FakeOnboarding : SourceOnboarding {
        val handle = Handle()
        var request: SourcePreparationRequest? = null
        var preparations = 0
        var activations = 0
        var cancellations = 0
        var previousName: String? = null
        var preparedResult: SourcePreparationResult =
            SourcePreparationResult.Prepared(handle, "https://provider.invalid")
        var activationResult: SourceActivationResult = SourceActivationResult.Activated
        var cancellationResult: SourceCancellationResult = SourceCancellationResult.Removed
        var onPrepare: (() -> Unit)? = null

        override suspend fun prepare(locator: String, insecureHttpApproved: Boolean): SourcePreparationResult =
            prepare(SourcePreparationRequest.M3u(locator, insecureHttpApproved))

        override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
            preparations++
            this.request = request
            onPrepare?.invoke()
            return preparedResult
        }

        override suspend fun activate(
            handle: SourcePreparationHandle,
            sourceName: String,
        ): SourceActivationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            activations++
            previousName = sourceName
            return activationResult
        }

        override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
            assertThat(handle).isSameInstanceAs(this.handle)
            cancellations++
            return cancellationResult
        }

        override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
    }

    private class PairingFixture {
        val authority = PairingAuthority()
        fun pair(): Pair<ProtectedSourcePrincipal, PairingGrant> {
            val proof = authority.begin().secret
            val claimed = authority.claim(proof, "Phone") as PairingClaim.AwaitingTvConfirmation
            assertThat(authority.approve(claimed.claimId)).isTrue()
            val grant = (authority.redeem(claimed.claimId, proof) as PairingRedemption.Granted).grant
            return ProtectedSourcePrincipal(authority, grant) to grant
        }
    }

    @Test
    fun m3uPrepareAndActivateDelegateToExistingPortWithoutReturningSecrets() = runBlocking {
        val fixture = PairingFixture()
        val (principal, _) = fixture.pair()
        val port = FakeOnboarding()
        val staging = ProtectedSourceStaging(port)
        val locator = "https://iptv.invalid/token=secret-value"
        assertThat(staging.prepare(principal, SourcePreparationRequest.M3u(locator)))
            .isEqualTo(SourceStagingResult.PREPARED)
        assertThat((port.request as SourcePreparationRequest.M3u).locator).isEqualTo(locator)
        assertThat(staging.activate(principal, "Домашнее TV")).isEqualTo(SourceStagingResult.ACTIVATED)
        assertThat(port.previousName).isEqualTo("Домашнее TV")
        assertThat(port.activations).isEqualTo(1)
        assertThat(staging.activate(principal, "Replay")).isEqualTo(SourceStagingResult.EMPTY)
        assertThat(principal.toString()).doesNotContain(locator)
        assertThat(principal.toString()).doesNotContain("secret")
    }

    @Test
    fun xtreamDelegatesCredentialInputWithoutEchoingPasswordOrUrl() = runBlocking {
        val (principal, _) = PairingFixture().pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        val request = SourcePreparationRequest.Xtream(
            endpoint = "https://xtream.invalid", username = "uid-123", password = "private-credential",
        )
        val result = stage.prepare(principal, request)
        assertThat(result).isEqualTo(SourceStagingResult.PREPARED)
        assertThat(port.request).isSameInstanceAs(request)
        assertThat(result.toString()).doesNotContain("uid-123")
        assertThat(result.toString()).doesNotContain("private-credential")
        assertThat(request.toString()).doesNotContain("private-credential")
    }

    @Test
    fun insecureApprovalFlagCannotBeSetByPhone() = runBlocking {
        val (principal, _) = PairingFixture().pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        assertThat(stage.prepare(principal,
            SourcePreparationRequest.M3u("http://iptv.invalid", insecureHttpApproved = true)))
            .isEqualTo(SourceStagingResult.INVALID_INPUT)
        assertThat(stage.prepare(principal,
            SourcePreparationRequest.Xtream("http://xtream.invalid", "u", "p", insecureHttpApproved = true)))
            .isEqualTo(SourceStagingResult.INVALID_INPUT)
        assertThat(port.preparations).isEqualTo(0)
    }

    @Test
    fun tvHttpApprovalAndNetworkPermissionRemainTvOwned() = runBlocking {
        val (principal, _) = PairingFixture().pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        port.preparedResult = SourcePreparationResult.InsecureTransportApprovalRequired
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("http://iptv.invalid")))
            .isEqualTo(SourceStagingResult.TV_HTTP_APPROVAL_REQUIRED)
        port.preparedResult = SourcePreparationResult.LocalNetworkAccessRequired
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("http://iptv.invalid")))
            .isEqualTo(SourceStagingResult.LOCAL_NETWORK_PERMISSION_REQUIRED)
        assertThat(stage.activate(principal, "x")).isEqualTo(SourceStagingResult.EMPTY)
    }

    @Test
    fun revokedSessionCannotPrepareActivateOrCancel() = runBlocking {
        val fixture = PairingFixture()
        val (principal, grant) = fixture.pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("https://iptv.invalid")))
            .isEqualTo(SourceStagingResult.PREPARED)
        assertThat(fixture.authority.revoke(grant.sessionId)).isTrue()
        assertThat(stage.activate(principal, "Name")).isEqualTo(SourceStagingResult.ACCESS_DENIED)
        assertThat(stage.cancel(principal)).isEqualTo(SourceStagingResult.ACCESS_DENIED)
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("https://iptv.invalid")))
            .isEqualTo(SourceStagingResult.ACCESS_DENIED)
        assertThat(port.activations).isEqualTo(0)
        assertThat(stage.discardRevoked(grant.sessionId)).isEqualTo(SourceStagingResult.CANCELED)
        assertThat(port.cancellations).isEqualTo(1)
    }

    @Test
    fun revokedWhilePreparingCannotRetainNewHandle() = runBlocking {
        val fixture = PairingFixture()
        val (principal, grant) = fixture.pair()
        val port = FakeOnboarding()
        port.onPrepare = { fixture.authority.revoke(grant.sessionId) }
        val stage = ProtectedSourceStaging(port)
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("https://iptv.invalid")))
            .isEqualTo(SourceStagingResult.ACCESS_DENIED)
        assertThat(port.cancellations).isEqualTo(1)
        assertThat(stage.discardRevoked(grant.sessionId)).isEqualTo(SourceStagingResult.EMPTY)
    }

    @Test
    fun duplicatePrepareDoesNotLeakHandleOrRunTwice() = runBlocking {
        val (principal, _) = PairingFixture().pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        val request = SourcePreparationRequest.M3u("https://iptv.invalid")
        assertThat(stage.prepare(principal, request)).isEqualTo(SourceStagingResult.PREPARED)
        assertThat(stage.prepare(principal, request)).isEqualTo(SourceStagingResult.ALREADY_PREPARED)
        assertThat(port.preparations).isEqualTo(1)
        assertThat(stage.cancel(principal)).isEqualTo(SourceStagingResult.CANCELED)
        assertThat(stage.cancel(principal)).isEqualTo(SourceStagingResult.EMPTY)
    }

    @Test
    fun cancellationPendingPreservesHandleForRetry() = runBlocking {
        val (principal, _) = PairingFixture().pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        stage.prepare(principal, SourcePreparationRequest.M3u("https://iptv.invalid"))
        port.cancellationResult = SourceCancellationResult.CleanupPending
        assertThat(stage.cancel(principal)).isEqualTo(SourceStagingResult.CLEANUP_PENDING)
        port.cancellationResult = SourceCancellationResult.Removed
        assertThat(stage.cancel(principal)).isEqualTo(SourceStagingResult.CANCELED)
        assertThat(port.cancellations).isEqualTo(2)
    }

    @Test
    fun invalidLengthsAndNamesRejectedBeforeExistingPort() = runBlocking {
        val (principal, _) = PairingFixture().pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("h".repeat(4_097))))
            .isEqualTo(SourceStagingResult.INVALID_INPUT)
        assertThat(stage.prepare(principal, SourcePreparationRequest.Xtream(
            endpoint = "https://xtream.invalid", username = "u", password = "p".repeat(2_049))))
            .isEqualTo(SourceStagingResult.INVALID_INPUT)
        assertThat(port.preparations).isEqualTo(0)
        assertThat(stage.prepare(principal, SourcePreparationRequest.M3u("https://iptv.invalid")))
            .isEqualTo(SourceStagingResult.PREPARED)
        assertThat(stage.activate(principal, "name\nnewline"))
            .isEqualTo(SourceStagingResult.INVALID_INPUT)
        assertThat(stage.activate(principal, "  padded  "))
            .isEqualTo(SourceStagingResult.INVALID_INPUT)
        assertThat(port.activations).isEqualTo(0)
    }

    @Test
    fun revocationCleanupDoesNotRequireAReplayedBearerToken() = runBlocking {
        val fixture = PairingFixture()
        val (principal, _) = fixture.pair()
        val port = FakeOnboarding()
        val stage = ProtectedSourceStaging(port)
        stage.prepare(principal, SourcePreparationRequest.M3u("https://iptv.invalid"))
        fixture.authority.stop()
        assertThat(stage.discardAllAfterStop()).isEqualTo(SourceStagingResult.CANCELED)
        assertThat(port.cancellations).isEqualTo(1)
        assertThat(stage.activate(principal, "Name")).isEqualTo(SourceStagingResult.ACCESS_DENIED)
    }
}

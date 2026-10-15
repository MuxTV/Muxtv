package app.muxtv.localcontrol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PairingAuthorityTest {
    private class FakeClock(var now: Long = 1_000) : PairingClock {
        override fun elapsedRealtimeMillis(): Long = now
    }

    private fun grant(authority: PairingAuthority, proof: String, label: String = "Phone"): PairingGrant {
        val claimed = authority.claim(proof, label) as PairingClaim.AwaitingTvConfirmation
        assertThat(authority.approve(claimed.claimId)).isTrue()
        return (authority.redeem(claimed.claimId, proof) as PairingRedemption.Granted).grant
    }

    @Test
    fun invitationIsRandomAndDoesNotLeakOnToString() {
        val authority = PairingAuthority()
        val first = authority.begin()
        val second = authority.begin()
        assertThat(first.secret).hasLength(64)
        assertThat(first.secret).matches("[0-9a-f]{64}")
        assertThat(first.secret).isNotEqualTo(second.secret)
        assertThat(first.toString()).doesNotContain(first.secret)
        assertThat(authority.claim(first.secret, "old")).isInstanceOf(PairingClaim.Denied::class.java)
    }

    @Test
    fun tvApprovalAndSecondProofAreRequiredForGrant() {
        val authority = PairingAuthority()
        val proof = authority.begin().secret
        val claim = authority.claim(proof, " Living Room ") as PairingClaim.AwaitingTvConfirmation
        assertThat(claim.clientLabel).isEqualTo("Living Room")
        assertThat(authority.redeem(claim.claimId, proof)).isInstanceOf(PairingRedemption.Denied::class.java)
        assertThat(authority.approve("wrong-request-id")).isFalse()
        assertThat(authority.redeem(claim.claimId, "a".repeat(64))).isInstanceOf(PairingRedemption.Denied::class.java)
        assertThat(authority.approve(claim.claimId)).isTrue()
        assertThat(authority.redeem(claim.claimId, "a".repeat(64))).isInstanceOf(PairingRedemption.Denied::class.java)
        val granted = authority.redeem(claim.claimId, proof) as PairingRedemption.Granted
        assertThat(granted.grant.toString()).doesNotContain(granted.grant.credential)
        assertThat(granted.grant.toString()).doesNotContain(granted.grant.sessionId)
        assertThat(authority.authorize(granted.grant.sessionId, granted.grant.credential, PairingCapability.SOURCE_CREATE)).isTrue()
        assertThat(authority.authorize(granted.grant.sessionId, granted.grant.credential, PairingCapability.SOURCE_TEST)).isTrue()
    }

    @Test
    fun replayFailsAfterRedemption() {
        val authority = PairingAuthority()
        val proof = authority.begin().secret
        val claimed = authority.claim(proof, "Phone") as PairingClaim.AwaitingTvConfirmation
        assertThat(authority.approve(claimed.claimId)).isTrue()
        assertThat(authority.redeem(claimed.claimId, proof)).isInstanceOf(PairingRedemption.Granted::class.java)
        assertThat(authority.redeem(claimed.claimId, proof)).isInstanceOf(PairingRedemption.Denied::class.java)
        assertThat(authority.claim(proof, "Phone")).isInstanceOf(PairingClaim.Denied::class.java)
    }

    @Test
    fun invalidProofBudgetCancelsInvitation() {
        val authority = PairingAuthority(policy = PairingPolicy(maxFailedProofs = 3))
        val proof = authority.begin().secret
        repeat(3) {
            assertThat(authority.claim("f".repeat(64), "Bad")).isInstanceOf(PairingClaim.Denied::class.java)
        }
        assertThat(authority.claim(proof, "Phone")).isInstanceOf(PairingClaim.Denied::class.java)
    }

    @Test
    fun invitationExpiresBeforeClaimAndBeforeRedemption() {
        val clock = FakeClock()
        val authority = PairingAuthority(clock = clock, policy = PairingPolicy(invitationTtlMillis = 1_000))
        val old = authority.begin().secret
        clock.now += 1_000
        assertThat(authority.claim(old, "Phone")).isInstanceOf(PairingClaim.Denied::class.java)
        val next = authority.begin().secret
        val claim = authority.claim(next, "Phone") as PairingClaim.AwaitingTvConfirmation
        clock.now += 1_000
        assertThat(authority.approve(claim.claimId)).isFalse()
        assertThat(authority.redeem(claim.claimId, next)).isInstanceOf(PairingRedemption.Denied::class.java)
    }

    @Test
    fun sessionExpiresAfterIdleAndAbsoluteLifetime() {
        val clock = FakeClock()
        val policy = PairingPolicy(sessionLifetimeMillis = 5_000, sessionIdleMillis = 2_000)
        val authority = PairingAuthority(clock = clock, policy = policy)
        val first = grant(authority, authority.begin().secret)
        clock.now += 1_999
        assertThat(authority.authorize(first.sessionId, first.credential, PairingCapability.SOURCE_TEST)).isTrue()
        clock.now += 2_000
        assertThat(authority.authorize(first.sessionId, first.credential, PairingCapability.SOURCE_TEST)).isFalse()
        val second = grant(authority, authority.begin().secret)
        repeat(4) {
            clock.now += 1_000
            assertThat(authority.authorize(second.sessionId, second.credential, PairingCapability.SOURCE_TEST)).isTrue()
        }
        clock.now += 1_000
        assertThat(authority.authorize(second.sessionId, second.credential, PairingCapability.SOURCE_TEST)).isFalse()
    }

    @Test
    fun wrongSecretDoesNotRefreshIdleLifetime() {
        val clock = FakeClock()
        val authority = PairingAuthority(clock = clock, policy = PairingPolicy(sessionIdleMillis = 1_000))
        val session = grant(authority, authority.begin().secret)
        clock.now += 999
        assertThat(authority.authorize(session.sessionId, "a".repeat(64), PairingCapability.SOURCE_TEST)).isFalse()
        clock.now += 1
        assertThat(authority.authorize(session.sessionId, session.credential, PairingCapability.SOURCE_TEST)).isFalse()
    }

    @Test
    fun revokeAndStopRemoveAccess() {
        val authority = PairingAuthority()
        val session = grant(authority, authority.begin().secret)
        assertThat(authority.revoke(session.sessionId)).isTrue()
        assertThat(authority.authorize(session.sessionId, session.credential, PairingCapability.SOURCE_CREATE)).isFalse()
        val second = grant(authority, authority.begin().secret)
        authority.stop()
        assertThat(authority.activeSessionCount()).isEqualTo(0)
        assertThat(authority.authorize(second.sessionId, second.credential, PairingCapability.SOURCE_TEST)).isFalse()
    }

    @Test
    fun sessionCapacityCannotSilentlyGrow() {
        val authority = PairingAuthority(policy = PairingPolicy(maxSessions = 1))
        grant(authority, authority.begin().secret)
        var refused = false
        try {
            authority.begin()
        } catch (_: IllegalStateException) {
            refused = true
        }
        assertThat(refused).isTrue()
        assertThat(authority.activeSessionCount()).isEqualTo(1)
    }

    @Test
    fun onlyOnePhoneMayClaimAnInvitation() {
        val authority = PairingAuthority()
        val invitation = authority.begin()
        val first = authority.claim(invitation.secret, "Alice") as PairingClaim.AwaitingTvConfirmation
        assertThat(authority.claim(invitation.secret, "Bob")).isInstanceOf(PairingClaim.Denied::class.java)
        assertThat(authority.approve(first.claimId)).isTrue()
        assertThat(authority.redeem(first.claimId, invitation.secret)).isInstanceOf(PairingRedemption.Granted::class.java)
    }

    @Test
    fun cancelAndClockRollbackInvalidatePendingAndSessions() {
        val clock = FakeClock()
        val authority = PairingAuthority(clock = clock)
        val invite = authority.begin()
        authority.cancelPending()
        assertThat(authority.claim(invite.secret, "Phone")).isInstanceOf(PairingClaim.Denied::class.java)
        val session = grant(authority, authority.begin().secret)
        clock.now -= 1
        assertThat(authority.authorize(session.sessionId, session.credential, PairingCapability.SOURCE_TEST)).isFalse()
    }

    @Test
    fun invalidClientLabelCannotBecomeTvConfirmationText() {
        val authority = PairingAuthority()
        val proof = authority.begin().secret
        assertThat(authority.claim(proof, "x\nTV approved")).isInstanceOf(PairingClaim.Denied::class.java)
        assertThat(authority.claim(proof, " ".repeat(10))).isInstanceOf(PairingClaim.Denied::class.java)
        assertThat(authority.claim(proof, "Phone")).isInstanceOf(PairingClaim.AwaitingTvConfirmation::class.java)
    }
}

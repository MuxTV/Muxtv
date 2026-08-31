package app.muxtv.database

import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackAccessCoordinatorTest {
    @Test
    fun `direct locator keeps existing credential-bound access policy path`() = runTest {
        val policy = RecordingAccessPolicy(PlaybackAccessDecision.SecureTransport)
        val coordinator = PlaybackAccessCoordinator(
            referenceResolver = PlaybackReferenceResolver { PlaybackReferenceResolution.Unhandled },
            accessPolicyResolver = policy,
        )

        val result = coordinator.resolve(CREDENTIAL_REF, DIRECT_HTTPS)

        assertThat(result).isEqualTo(
            CoordinatedPlaybackAccess.Ready(DIRECT_HTTPS, insecureHttpApproved = false),
        )
        assertThat(policy.resolveCalls).containsExactly(CREDENTIAL_REF to DIRECT_HTTPS)
        assertThat(policy.preapprovedCalls).isEmpty()
    }

    @Test
    fun `resolved https locator does not pass Xtream credential id into legacy M3U policy`() = runTest {
        val policy = RecordingAccessPolicy(PlaybackAccessDecision.SecureTransport)
        val coordinator = PlaybackAccessCoordinator(
            referenceResolver = PlaybackReferenceResolver {
                PlaybackReferenceResolution.Ready(EPHEMERAL_HTTPS, insecureHttpPreapproved = false)
            },
            accessPolicyResolver = policy,
        )

        val result = coordinator.resolve(CREDENTIAL_REF, OPAQUE_REFERENCE)

        assertThat(result).isEqualTo(
            CoordinatedPlaybackAccess.Ready(EPHEMERAL_HTTPS, insecureHttpApproved = false),
        )
        assertThat(policy.resolveCalls).containsExactly("" to EPHEMERAL_HTTPS)
        assertThat(policy.preapprovedCalls).isEmpty()
    }

    @Test
    fun `resolved approved http still passes through final preapproved access-policy validation`() = runTest {
        val policy = RecordingAccessPolicy(PlaybackAccessDecision.Approved)
        val coordinator = PlaybackAccessCoordinator(
            referenceResolver = PlaybackReferenceResolver {
                PlaybackReferenceResolution.Ready(EPHEMERAL_HTTP, insecureHttpPreapproved = true)
            },
            accessPolicyResolver = policy,
        )

        val result = coordinator.resolve(CREDENTIAL_REF, OPAQUE_REFERENCE)

        assertThat(result).isEqualTo(
            CoordinatedPlaybackAccess.Ready(EPHEMERAL_HTTP, insecureHttpApproved = true),
        )
        assertThat(policy.resolveCalls).isEmpty()
        assertThat(policy.preapprovedCalls).containsExactly(EPHEMERAL_HTTP)
    }

    @Test
    fun `provider approval requirement remains typed without exposing generated locator`() = runTest {
        val coordinator = PlaybackAccessCoordinator(
            referenceResolver = PlaybackReferenceResolver {
                PlaybackReferenceResolution.ApprovalRequired("http://provider.example:80")
            },
            accessPolicyResolver = RecordingAccessPolicy(PlaybackAccessDecision.SecureTransport),
        )

        val result = coordinator.resolve(CREDENTIAL_REF, OPAQUE_REFERENCE)

        assertThat(result).isEqualTo(
            CoordinatedPlaybackAccess.ApprovalRequired("http://provider.example:80"),
        )
        assertThat(result.toString()).doesNotContain("provider.example")
    }

    @Test
    fun `reference failures map to existing playback unavailable reasons`() = runTest {
        val cases = listOf(
            PlaybackReferenceResolution.InvalidReference to PlaybackAccessUnavailableReason.InvalidLocator,
            PlaybackReferenceResolution.CredentialNotFound to PlaybackAccessUnavailableReason.CredentialNotFound,
            PlaybackReferenceResolution.CredentialCorrupted to PlaybackAccessUnavailableReason.CredentialCorrupted,
            PlaybackReferenceResolution.CredentialUnavailable to PlaybackAccessUnavailableReason.CredentialUnavailable,
        )

        for ((referenceResult, expectedReason) in cases) {
            val coordinator = PlaybackAccessCoordinator(
                referenceResolver = PlaybackReferenceResolver { referenceResult },
                accessPolicyResolver = RecordingAccessPolicy(PlaybackAccessDecision.SecureTransport),
            )

            assertThat(coordinator.resolve(CREDENTIAL_REF, OPAQUE_REFERENCE))
                .isEqualTo(CoordinatedPlaybackAccess.Unavailable(expectedReason))
        }
    }

    @Test(expected = CancellationException::class)
    fun `reference resolver cancellation propagates`() = runTest {
        val coordinator = PlaybackAccessCoordinator(
            referenceResolver = PlaybackReferenceResolver { throw CancellationException("superseded") },
            accessPolicyResolver = RecordingAccessPolicy(PlaybackAccessDecision.SecureTransport),
        )

        coordinator.resolve(CREDENTIAL_REF, OPAQUE_REFERENCE)
    }

    private companion object {
        const val CREDENTIAL_REF = "00000000-0000-0000-0000-000000000234"
        const val DIRECT_HTTPS = "https://m3u.example/live.m3u8"
        const val OPAQUE_REFERENCE = "muxtv-provider://xtream/live/707/m3u8"
        const val EPHEMERAL_HTTPS = "https://provider.example/live/user/pass/707.m3u8"
        const val EPHEMERAL_HTTP = "http://provider.example/live/user/pass/707.ts"
    }
}

private class RecordingAccessPolicy(
    private val decision: PlaybackAccessDecision,
) : PlaybackAccessPolicyResolver {
    val resolveCalls = mutableListOf<Pair<String, String>>()
    val preapprovedCalls = mutableListOf<String>()

    override suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessDecision {
        resolveCalls += credentialRef to playbackLocator
        return decision
    }

    override suspend fun resolvePreapproved(playbackLocator: String): PlaybackAccessDecision {
        preapprovedCalls += playbackLocator
        return decision
    }

    override suspend fun approve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unchanged

    override suspend fun revoke(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unchanged

    override suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult =
        PlaybackAccessMutationResult.Unchanged
}

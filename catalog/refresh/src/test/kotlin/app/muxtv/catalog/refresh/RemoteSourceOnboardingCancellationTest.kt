package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemoteSourceOnboardingCancellationTest {
    @Test
    fun cancelPropagatesSourceCleanupCancellationAndRetainsCredential() = runTest {
        val credentials = CancellationCredentialStore()
        val cancellation = CancellationException("source cleanup cancelled")
        val onboarding = onboarding(
            credentials = credentials,
            cleanup = { _, _ -> throw cancellation },
            activate = { error("Activation must not run during cancel.") },
        )
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val actual = captureCancellation { onboarding.cancel(prepared.token) }

        assertThat(actual).isSameInstanceAs(cancellation)
        assertThat(credentials.read(prepared.token.credentialId()))
            .isInstanceOf(CredentialReadResult.Found::class.java)
    }

    @Test
    fun failedActivationPropagatesCleanupCancellationAndRetainsCredential() = runTest {
        val credentials = CancellationCredentialStore()
        val cancellation = CancellationException("source cleanup cancelled")
        val onboarding = onboarding(
            credentials = credentials,
            cleanup = { _, _ -> throw cancellation },
            activate = {
                RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Timeout)
            },
        )
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val actual = captureCancellation {
            onboarding.activate(prepared.token, "Primary IPTV")
        }

        assertThat(actual).isSameInstanceAs(cancellation)
        assertThat(credentials.read(prepared.token.credentialId()))
            .isInstanceOf(CredentialReadResult.Found::class.java)
    }

    @Test
    fun activationCancellationIsNotReplacedByCleanupCancellation() = runTest {
        val credentials = CancellationCredentialStore()
        val activationCancellation = CancellationException("activation cancelled")
        val cleanupCancellation = CancellationException("cleanup cancelled")
        val onboarding = onboarding(
            credentials = credentials,
            cleanup = { _, _ -> throw cleanupCancellation },
            activate = { throw activationCancellation },
        )
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val actual = captureCancellation {
            onboarding.activate(prepared.token, "Primary IPTV")
        }

        assertThat(actual).isSameInstanceAs(activationCancellation)
        assertThat(credentials.read(prepared.token.credentialId()))
            .isInstanceOf(CredentialReadResult.Found::class.java)
    }

    private fun onboarding(
        credentials: CredentialStore,
        cleanup: suspend (String, String) -> RemoteSourceMetadataCleanupResult,
        activate: suspend (RemoteSourceRefreshRequest) -> RemoteSourceRefreshResult,
    ): RemoteSourceOnboarding = DefaultRemoteSourceOnboarding(
        accessManager = RemoteSourceAccessManager(credentials),
        activator = RemoteSourceActivator(activate),
        activationCleanup = RemoteSourceActivationCleanup(cleanup),
    )

    private suspend fun captureCancellation(block: suspend () -> Unit): CancellationException = try {
        block()
        error("CancellationException must be rethrown.")
    } catch (error: CancellationException) {
        error
    }
}

private class CancellationCredentialStore : CredentialStore {
    private val records = linkedMapOf<CredentialId, ByteArray>()

    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult {
        records.remove(id)?.fill(0)
        records[id] = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult {
        val bytes = records[id] ?: return CredentialReadResult.NotFound
        return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
    }

    override suspend fun remove(id: CredentialId): CredentialRemoveResult {
        val removed = records.remove(id) ?: return CredentialRemoveResult.NotFound
        removed.fill(0)
        return CredentialRemoveResult.Removed
    }

    override suspend fun reset(): CredentialResetResult {
        records.values.forEach { it.fill(0) }
        records.clear()
        return CredentialResetResult.Reset
    }
}

package app.muxtv.sources

import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.refresh.XtreamSourceAccessManager
import app.muxtv.catalog.refresh.XtreamSourcePreparer
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.PendingSourcePreparation
import app.muxtv.database.PendingSourcePreparationStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AppSourceOnboardingLocalNetworkPreflightTest {
    @Test
    fun `local m3u is blocked before durable onboarding`() = runBlocking {
        val remote = RecordingRemoteSourceOnboarding()
        val onboarding = AppSourceOnboarding(
            delegate = DurableRemoteSourceOnboarding(
                delegate = remote,
                registry = NoOpPendingSourcePreparationStore,
            ),
            localNetworkAccessRequired = { true },
        )

        val result = onboarding.prepare(
            SourcePreparationRequest.M3u(
                locator = "https://192.168.1.20/playlist.m3u",
            ),
        )

        assertThat(result).isEqualTo(SourcePreparationResult.LocalNetworkAccessRequired)
        assertThat(remote.prepareCalls).isEqualTo(0)
    }

    @Test
    fun `local xtream is blocked before credential preparation`() = runBlocking {
        val credentials = PreflightRecordingCredentialStore()
        val onboarding = AppSourceOnboarding(
            delegate = DurableRemoteSourceOnboarding(
                delegate = RecordingRemoteSourceOnboarding(),
                registry = NoOpPendingSourcePreparationStore,
            ),
            xtreamPreparer = XtreamSourcePreparer(
                XtreamSourceAccessManager(credentials),
            ),
            localNetworkAccessRequired = { true },
        )

        val result = onboarding.prepare(
            SourcePreparationRequest.Xtream(
                endpoint = "https://192.168.1.20",
                username = "alice",
                password = "secret",
            ),
        )

        assertThat(result).isEqualTo(SourcePreparationResult.LocalNetworkAccessRequired)
        assertThat(credentials.putCalls).isEqualTo(0)
    }

    @Test
    fun `allowed endpoint keeps existing onboarding path`() = runBlocking {
        val remote = RecordingRemoteSourceOnboarding(
            preparationResult = RemoteSourcePreparationResult.InvalidAccess,
        )
        val onboarding = AppSourceOnboarding(
            delegate = DurableRemoteSourceOnboarding(
                delegate = remote,
                registry = NoOpPendingSourcePreparationStore,
            ),
            localNetworkAccessRequired = { false },
        )

        onboarding.prepare(
            SourcePreparationRequest.M3u(
                locator = "https://provider.example/playlist.m3u",
            ),
        )

        assertThat(remote.prepareCalls).isEqualTo(1)
    }
}

private class RecordingRemoteSourceOnboarding(
    private val preparationResult: RemoteSourcePreparationResult =
        RemoteSourcePreparationResult.InvalidAccess,
) : RemoteSourceOnboarding {
    var prepareCalls: Int = 0

    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult {
        prepareCalls += 1
        return preparationResult
    }

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = error("Activation is outside this test.")

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult =
        error("Cancellation is outside this test.")
}

private class PreflightRecordingCredentialStore : CredentialStore {
    var putCalls: Int = 0

    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult {
        putCalls += 1
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult = CredentialReadResult.NotFound

    override suspend fun remove(id: CredentialId): CredentialRemoveResult = CredentialRemoveResult.NotFound

    override suspend fun reset(): CredentialResetResult = CredentialResetResult.Reset
}

private object NoOpPendingSourcePreparationStore : PendingSourcePreparationStore {
    override suspend fun upsert(preparation: PendingSourcePreparation) = Unit

    override suspend fun remove(preparationId: String): Boolean = false

    override suspend fun get(preparationId: String): PendingSourcePreparation? = null

    override suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparation? = null

    override suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation> = emptyList()
}

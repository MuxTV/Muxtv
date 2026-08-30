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
import app.muxtv.catalog.refresh.SourceAccessReference
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

class AppSourceOnboardingXtreamPreparationTest {
    @Test
    fun `xtream request prepares a durable opaque transaction`() = runBlocking {
        val pendingStore = RecordingPendingSourcePreparationStore()
        val onboarding = AppSourceOnboarding(
            delegate = DurableRemoteSourceOnboarding(
                delegate = LegacyOnboardingMustNotBeUsed,
                registry = pendingStore,
            ),
            xtreamPreparer = XtreamSourcePreparer(
                XtreamSourceAccessManager(AcceptingCredentialStore),
            ),
        )

        val result = onboarding.prepare(
            SourcePreparationRequest.Xtream(
                endpoint = "https://provider.example/player_api.php?ignored=true",
                username = "alice",
                password = "secret",
            ),
        )

        assertThat(result).isInstanceOf(SourcePreparationResult.Prepared::class.java)
        val prepared = result as SourcePreparationResult.Prepared
        assertThat(prepared.displayEndpoint).isEqualTo("https://provider.example")
        assertThat(prepared.handle.toString()).isEqualTo("SourcePreparationHandle(<redacted>)")
        assertThat(pendingStore.upserted).hasSize(1)
        assertThat(pendingStore.upserted.single().preparationId).startsWith("muxtv-access:v1:xtream:")
    }

    @Test
    fun `durable xtream preparation restores as an opaque handle`() = runBlocking {
        val pendingStore = RecordingPendingSourcePreparationStore()
        val accessReference = SourceAccessReference.xtream(
            CredentialId.parse("00000000-0000-4000-8000-000000000224"),
        )
        pendingStore.upsert(
            PendingSourcePreparation(
                preparationId = accessReference.value,
                scheme = "https",
                host = "provider.example",
                createdAtEpochMillis = 1L,
                expiresAtEpochMillis = Long.MAX_VALUE,
            ),
        )
        val onboarding = AppSourceOnboarding(
            delegate = DurableRemoteSourceOnboarding(
                delegate = LegacyOnboardingMustNotBeUsed,
                registry = pendingStore,
            ),
        )

        val restored = onboarding.restoreLatestPrepared()

        assertThat(restored).isNotNull()
        assertThat(restored!!.displayEndpoint).isEqualTo("https://provider.example")
        assertThat(restored.handle.toString()).isEqualTo("SourcePreparationHandle(<redacted>)")
        assertThat(pendingStore.upserted.single().preparationId).isEqualTo(accessReference.value)
    }
}

private object AcceptingCredentialStore : CredentialStore {
    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult = CredentialWriteResult.Stored

    override suspend fun read(id: CredentialId): CredentialReadResult = CredentialReadResult.NotFound

    override suspend fun remove(id: CredentialId): CredentialRemoveResult = CredentialRemoveResult.Removed

    override suspend fun reset(): CredentialResetResult = CredentialResetResult.Reset
}

private object LegacyOnboardingMustNotBeUsed : RemoteSourceOnboarding {
    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult =
        error("Legacy M3U onboarding must not handle an Xtream request.")

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = error("Activation is outside the preparation slice.")

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult =
        error("Legacy M3U cancellation must not handle an Xtream preparation.")
}

private class RecordingPendingSourcePreparationStore : PendingSourcePreparationStore {
    val upserted = mutableListOf<PendingSourcePreparation>()

    override suspend fun upsert(preparation: PendingSourcePreparation) {
        upserted += preparation
    }

    override suspend fun remove(preparationId: String): Boolean = false

    override suspend fun get(preparationId: String): PendingSourcePreparation? =
        upserted.lastOrNull { it.preparationId == preparationId }

    override suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparation? =
        upserted.lastOrNull { it.expiresAtEpochMillis > nowEpochMillis }

    override suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation> = upserted
        .filter { it.expiresAtEpochMillis <= nowEpochMillis }
        .take(limit)
}

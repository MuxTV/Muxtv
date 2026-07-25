package app.muxtv.catalog.onboarding

import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.database.PendingSourcePreparation
import app.muxtv.database.PendingSourcePreparationStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableRemoteSourceOnboardingRestoreFallbackTest {
    @Test
    fun invalidNewestRowDoesNotBlockOlderValidPreparation() = runTest {
        val validToken = token(22)
        val valid = pending(
            id = validToken.value,
            host = "example.com",
            createdAt = 1_000L,
        )
        val invalid = pending(
            id = "not-a-credential-id",
            host = "invalid.example.com",
            createdAt = 2_000L,
        )
        val registry = RestoreRegistry(mutableListOf(valid, invalid))
        val onboarding = DurableRemoteSourceOnboarding(
            delegate = NoOpOnboarding,
            registry = registry,
            currentTimeMillis = { 10_000L },
        )

        val restored = onboarding.restoreLatestPrepared()

        assertThat(restored).isEqualTo(
            RemoteSourcePreparationResult.Prepared(
                token = validToken,
                scheme = "https",
                host = "example.com",
            ),
        )
        assertThat(registry.rows).containsExactly(valid)
    }

    private fun pending(
        id: String,
        host: String,
        createdAt: Long,
    ) = PendingSourcePreparation(
        preparationId = id,
        scheme = "https",
        host = host,
        createdAtEpochMillis = createdAt,
        expiresAtEpochMillis = 100_000L,
    )

    private fun token(index: Int): RemoteSourcePreparationToken =
        RemoteSourcePreparationToken.parse(
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
        )
}

private object NoOpOnboarding : RemoteSourceOnboarding {
    override suspend fun prepare(input: RemoteSourceOnboardingInput) =
        RemoteSourcePreparationResult.InvalidAccess

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = error("Not used")

    override suspend fun cancel(token: RemoteSourcePreparationToken) =
        RemoteSourceCancellationResult.NotFound
}

private class RestoreRegistry(
    val rows: MutableList<PendingSourcePreparation>,
) : PendingSourcePreparationStore {
    override suspend fun upsert(preparation: PendingSourcePreparation) = Unit

    override suspend fun remove(preparationId: String): Boolean =
        rows.removeAll { it.preparationId == preparationId }

    override suspend fun get(preparationId: String): PendingSourcePreparation? =
        rows.firstOrNull { it.preparationId == preparationId }

    override suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparation? =
        rows
            .filter { it.expiresAtEpochMillis > nowEpochMillis }
            .maxWithOrNull(
                compareBy<PendingSourcePreparation>(
                    PendingSourcePreparation::createdAtEpochMillis,
                    PendingSourcePreparation::preparationId,
                ),
            )

    override suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation> = emptyList()
}

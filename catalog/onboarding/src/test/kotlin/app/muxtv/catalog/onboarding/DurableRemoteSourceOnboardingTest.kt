package app.muxtv.catalog.onboarding

import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceMetadataCleanupFailure
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.database.PendingSourcePreparation
import app.muxtv.database.PendingSourcePreparationStore
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableRemoteSourceOnboardingTest {
    @Test
    fun preparePersistsOnlyOpaqueIdAndSanitizedEndpoint() = runTest {
        val token = token(1)
        val delegate = FakeOnboarding(
            prepareResult = RemoteSourcePreparationResult.Prepared(
                token = token,
                scheme = "https",
                host = "example.com",
            ),
        )
        val registry = FakeRegistry()
        val onboarding = DurableRemoteSourceOnboarding(
            delegate = delegate,
            registry = registry,
            currentTimeMillis = { 1_000L },
        )

        val result = onboarding.prepare(
            RemoteSourceOnboardingInput(
                locator = "https://example.com/list.m3u?token=secret",
                sensitiveHeaders = mapOf("Authorization" to "Bearer secret"),
            ),
        )

        assertThat(result).isInstanceOf(RemoteSourcePreparationResult.Prepared::class.java)
        val stored = registry.rows.single()
        assertThat(stored.preparationId).isEqualTo(token.value)
        assertThat(stored.scheme).isEqualTo("https")
        assertThat(stored.host).isEqualTo("example.com")
        assertThat(stored.createdAtEpochMillis).isEqualTo(1_000L)
        assertThat(stored.expiresAtEpochMillis).isEqualTo(86_401_000L)
        assertThat(stored.toString()).doesNotContain(token.value)
        assertThat(stored.toString()).doesNotContain("secret")
    }

    @Test
    fun registryFailureRollsBackPreparedCredential() = runTest {
        val token = token(2)
        val delegate = FakeOnboarding(
            prepareResult = RemoteSourcePreparationResult.Prepared(
                token = token,
                scheme = "https",
                host = "example.com",
            ),
        )
        val registry = FakeRegistry(upsertFailure = IOException("database unavailable"))
        val onboarding = DurableRemoteSourceOnboarding(
            delegate = delegate,
            registry = registry,
            currentTimeMillis = { 2_000L },
        )

        val result = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        )

        assertThat(result).isEqualTo(
            RemoteSourcePreparationResult.CredentialUnavailable(
                CredentialUnavailableReason.IoFailure,
            ),
        )
        assertThat(delegate.cancelledTokens).containsExactly(token)
    }

    @Test
    fun successfulActivationRemovesRegistryRow() = runTest {
        val token = token(3)
        val registry = FakeRegistry(rows = mutableListOf(pending(token, expiresAt = 100_000L)))
        val delegate = FakeOnboarding(
            activateResult = RemoteSourceActivationResult.Activated(
                sourceId = "source-opaque",
                revisionNumber = 1,
                previousRevisionNumber = 0,
                entryCount = 10,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        val onboarding = DurableRemoteSourceOnboarding(delegate, registry) { 3_000L }

        onboarding.activate(token, "Primary")

        assertThat(registry.rows).isEmpty()
    }

    @Test
    fun incompleteFailureRetainsRegistryForStartupRetry() = runTest {
        val token = token(4)
        val registry = FakeRegistry(rows = mutableListOf(pending(token, expiresAt = 100_000L)))
        val delegate = FakeOnboarding(
            activateResult = RemoteSourceActivationResult.Failed(
                failure = RemoteSourceActivationFailure.Unexpected,
                credentialCleanupFailure = null,
                sourceCleanupFailure = RemoteSourceMetadataCleanupFailure.MetadataRetained,
            ),
        )
        val onboarding = DurableRemoteSourceOnboarding(delegate, registry) { 4_000L }

        onboarding.activate(token, "Primary")

        assertThat(registry.rows.map(PendingSourcePreparation::preparationId))
            .containsExactly(token.value)
    }

    @Test
    fun cleanupExpiredIsBoundedAndRemovesOnlyCompletedCancellations() = runTest {
        val rows = (1..60).mapTo(mutableListOf()) { index ->
            pending(token(index), expiresAt = 5_000L)
        }
        val registry = FakeRegistry(rows = rows)
        val delegate = FakeOnboarding(
            cancellationResult = { token ->
                if (token.value.endsWith("1")) {
                    RemoteSourceCancellationResult.MetadataRetained
                } else {
                    RemoteSourceCancellationResult.Removed
                }
            },
        )
        val onboarding = DurableRemoteSourceOnboarding(delegate, registry) { 10_000L }

        val summary = onboarding.cleanupExpired()

        assertThat(summary.inspected).isEqualTo(50)
        assertThat(delegate.cancelledTokens).hasSize(50)
        assertThat(summary.removed + summary.retained + summary.failures).isEqualTo(50)
        assertThat(registry.lastExpiredLimit).isEqualTo(50)
        assertThat(registry.rows).hasSize(60 - summary.removed)
    }

    private fun pending(
        token: RemoteSourcePreparationToken,
        expiresAt: Long,
    ): PendingSourcePreparation = PendingSourcePreparation(
        preparationId = token.value,
        scheme = "https",
        host = "example.com",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = expiresAt,
    )

    private fun token(index: Int): RemoteSourcePreparationToken =
        RemoteSourcePreparationToken.parse("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
}

private class FakeOnboarding(
    private val prepareResult: RemoteSourcePreparationResult =
        RemoteSourcePreparationResult.InvalidAccess,
    private val activateResult: RemoteSourceActivationResult =
        RemoteSourceActivationResult.Failed(
            failure = RemoteSourceActivationFailure.Unexpected,
            credentialCleanupFailure = null,
            sourceCleanupFailure = null,
        ),
    private val cancellationResult: (RemoteSourcePreparationToken) -> RemoteSourceCancellationResult = {
        RemoteSourceCancellationResult.Removed
    },
) : RemoteSourceOnboarding {
    val cancelledTokens = mutableListOf<RemoteSourcePreparationToken>()

    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult =
        prepareResult

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = activateResult

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult {
        cancelledTokens += token
        return cancellationResult(token)
    }
}

private class FakeRegistry(
    val rows: MutableList<PendingSourcePreparation> = mutableListOf(),
    private val upsertFailure: Exception? = null,
) : PendingSourcePreparationStore {
    var lastExpiredLimit: Int? = null

    override suspend fun upsert(preparation: PendingSourcePreparation) {
        upsertFailure?.let { throw it }
        rows.removeAll { it.preparationId == preparation.preparationId }
        rows += preparation
    }

    override suspend fun remove(preparationId: String): Boolean =
        rows.removeAll { it.preparationId == preparationId }

    override suspend fun get(preparationId: String): PendingSourcePreparation? =
        rows.firstOrNull { it.preparationId == preparationId }

    override suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation> {
        lastExpiredLimit = limit
        return rows
            .filter { it.expiresAtEpochMillis <= nowEpochMillis }
            .sortedWith(
                compareBy<PendingSourcePreparation>(
                    PendingSourcePreparation::expiresAtEpochMillis,
                    PendingSourcePreparation::createdAtEpochMillis,
                    PendingSourcePreparation::preparationId,
                ),
            )
            .take(limit)
    }
}

package app.muxtv.catalog.onboarding

import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.database.PendingSourcePreparation
import app.muxtv.database.PendingSourcePreparationStore
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableRemoteSourceOnboardingRollbackTest {
    @Test
    fun registryCancellationIsNotReplacedByRollbackCancellation() = runTest {
        val token = token(1)
        val registryCancellation = CancellationException("registry cancelled")
        val rollbackCancellation = CancellationException("rollback cancelled")
        val onboarding = DurableRemoteSourceOnboarding(
            delegate = RollbackDelegate(
                prepared = prepared(token),
                cancelFailure = rollbackCancellation,
            ),
            registry = FailingPreparationStore(registryCancellation),
            currentTimeMillis = { 1_000L },
        )

        val actual = try {
            onboarding.prepare(RemoteSourceOnboardingInput("https://example.com/list.m3u"))
            error("CancellationException must be rethrown.")
        } catch (error: CancellationException) {
            error
        }

        assertThat(actual).isSameInstanceAs(registryCancellation)
    }

    @Test
    fun registryIoFailureRemainsTypedWhenRollbackFails() = runTest {
        val token = token(2)
        val onboarding = DurableRemoteSourceOnboarding(
            delegate = RollbackDelegate(
                prepared = prepared(token),
                cancelFailure = CancellationException("rollback cancelled"),
            ),
            registry = FailingPreparationStore(IOException("registry unavailable")),
            currentTimeMillis = { 2_000L },
        )

        val result = onboarding.prepare(
            RemoteSourceOnboardingInput("https://example.com/list.m3u"),
        )

        assertThat(result).isEqualTo(
            RemoteSourcePreparationResult.CredentialUnavailable(
                CredentialUnavailableReason.IoFailure,
            ),
        )
    }

    private fun prepared(token: RemoteSourcePreparationToken) =
        RemoteSourcePreparationResult.Prepared(
            token = token,
            scheme = "https",
            host = "example.com",
        )

    private fun token(index: Int): RemoteSourcePreparationToken =
        RemoteSourcePreparationToken.parse(
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
        )
}

private class RollbackDelegate(
    private val prepared: RemoteSourcePreparationResult,
    private val cancelFailure: Exception,
) : RemoteSourceOnboarding {
    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult =
        prepared

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult = RemoteSourceActivationResult.Failed(
        failure = RemoteSourceActivationFailure.Unexpected,
        credentialCleanupFailure = null,
        sourceCleanupFailure = null,
    )

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult {
        throw cancelFailure
    }
}

private class FailingPreparationStore(
    private val failure: Exception,
) : PendingSourcePreparationStore {
    override suspend fun upsert(preparation: PendingSourcePreparation) {
        throw failure
    }

    override suspend fun remove(preparationId: String): Boolean = false

    override suspend fun get(preparationId: String): PendingSourcePreparation? = null

    override suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparation? = null

    override suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparation> = emptyList()
}

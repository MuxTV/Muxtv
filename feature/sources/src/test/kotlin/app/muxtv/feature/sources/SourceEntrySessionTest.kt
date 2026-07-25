package app.muxtv.feature.sources

import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceEntrySessionTest {
    @Test
    fun prepareExposesOnlySanitizedEndpoint() = runTest {
        val token = token(1)
        val onboarding = FakeSourceEntryOnboarding(
            prepareResult = RemoteSourcePreparationResult.Prepared(
                token = token,
                scheme = "https",
                host = "example.com",
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.prepare("https://user:password@example.com/list.m3u?token=secret")

        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("https://example.com"),
        )
        assertThat(session.state.value.toString()).doesNotContain(token.value)
        assertThat(session.state.value.toString()).doesNotContain("secret")
    }

    @Test
    fun httpRequiresExplicitApprovalBeforeSecondPrepare() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResults = ArrayDeque(
                listOf(
                    RemoteSourcePreparationResult.InsecureTransportApprovalRequired,
                    RemoteSourcePreparationResult.Prepared(
                        token = token(2),
                        scheme = "http",
                        host = "192.168.1.10",
                    ),
                ),
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.prepare("http://192.168.1.10/list.m3u")
        assertThat(session.state.value).isEqualTo(SourceEntryUiState.HttpApprovalRequired)

        session.approveInsecureHttp()

        assertThat(onboarding.prepareInputs).hasSize(2)
        assertThat(onboarding.prepareInputs[0].insecureHttpApproved).isFalse()
        assertThat(onboarding.prepareInputs[1].insecureHttpApproved).isTrue()
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("http://192.168.1.10"),
        )
    }

    @Test
    fun restoreRecoversPreparedEndpointWithoutRouteArguments() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            restored = RemoteSourcePreparationResult.Prepared(
                token = token(3),
                scheme = "https",
                host = "provider.example",
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.restore()

        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("https://provider.example"),
        )
    }

    @Test
    fun successfulActivationCompletesAndClearsPreparedSession() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResult = RemoteSourcePreparationResult.Prepared(
                token = token(4),
                scheme = "https",
                host = "example.com",
            ),
            activationResult = RemoteSourceActivationResult.Activated(
                sourceId = "source-opaque",
                revisionNumber = 1,
                previousRevisionNumber = 0,
                entryCount = 10,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        val session = SourceEntrySession(onboarding)
        session.prepare("https://example.com/list.m3u")

        session.activate("Primary")

        assertThat(session.state.value).isEqualTo(SourceEntryUiState.Completed)
        assertThat(onboarding.activatedSourceNames).containsExactly("Primary")
        assertThat(session.cancel()).isTrue()
    }

    @Test
    fun failedCleanupKeepsPreparedSessionForRetry() = runTest {
        val token = token(5)
        val onboarding = FakeSourceEntryOnboarding(
            restored = RemoteSourcePreparationResult.Prepared(
                token = token,
                scheme = "https",
                host = "example.com",
            ),
            cancellationResult = RemoteSourceCancellationResult.MetadataRetained,
        )
        val session = SourceEntrySession(onboarding)
        session.restore()

        val completed = session.cancel()

        assertThat(completed).isFalse()
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Failed(
                reason = SourceEntryFailure.CleanupPending,
                cleanupPending = true,
            ),
        )
        assertThat(onboarding.cancelledTokens).containsExactly(token)
    }

    private fun token(index: Int): RemoteSourcePreparationToken =
        RemoteSourcePreparationToken.parse(
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
        )
}

private class FakeSourceEntryOnboarding(
    private val prepareResult: RemoteSourcePreparationResult =
        RemoteSourcePreparationResult.InvalidAccess,
    private val prepareResults: ArrayDeque<RemoteSourcePreparationResult> = ArrayDeque(),
    private val restored: RemoteSourcePreparationResult.Prepared? = null,
    private val activationResult: RemoteSourceActivationResult = RemoteSourceActivationResult.Failed(
        failure = app.muxtv.catalog.refresh.RemoteSourceActivationFailure.Unexpected,
        credentialCleanupFailure = null,
        sourceCleanupFailure = null,
    ),
    private val cancellationResult: RemoteSourceCancellationResult =
        RemoteSourceCancellationResult.Removed,
) : SourceEntryOnboarding {
    val prepareInputs = mutableListOf<RemoteSourceOnboardingInput>()
    val activatedSourceNames = mutableListOf<String>()
    val cancelledTokens = mutableListOf<RemoteSourcePreparationToken>()

    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult {
        prepareInputs += input
        return if (prepareResults.isEmpty()) prepareResult else prepareResults.removeFirst()
    }

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult {
        activatedSourceNames += sourceName
        return activationResult
    }

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult {
        cancelledTokens += token
        return cancellationResult
    }

    override suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared? = restored
}

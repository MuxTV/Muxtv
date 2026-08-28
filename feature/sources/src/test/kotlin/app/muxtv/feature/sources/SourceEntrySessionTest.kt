package app.muxtv.feature.sources

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationFailure
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceEntrySessionTest {
    @Test
    fun prepareExposesOnlySanitizedEndpoint() = runTest {
        val handle = TestPreparationHandle(1)
        val onboarding = FakeSourceEntryOnboarding(
            prepareResult = SourcePreparationResult.Prepared(
                handle = handle,
                displayEndpoint = "https://example.com",
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.prepare("https://user:password@example.com/list.m3u?token=secret")

        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("https://example.com"),
        )
        assertThat(session.state.value.toString()).doesNotContain("secret")
        assertThat(handle.toString()).doesNotContain("1")
    }

    @Test
    fun httpRequiresExplicitApprovalBeforeSecondPrepare() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResults = ArrayDeque(
                listOf(
                    SourcePreparationResult.InsecureTransportApprovalRequired,
                    SourcePreparationResult.Prepared(
                        handle = TestPreparationHandle(2),
                        displayEndpoint = "http://192.168.1.10",
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
            restored = SourcePreparationResult.Prepared(
                handle = TestPreparationHandle(3),
                displayEndpoint = "https://provider.example",
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
            prepareResult = SourcePreparationResult.Prepared(
                handle = TestPreparationHandle(4),
                displayEndpoint = "https://example.com",
            ),
            activationResult = SourceActivationResult.Activated,
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
        val handle = TestPreparationHandle(5)
        val onboarding = FakeSourceEntryOnboarding(
            restored = SourcePreparationResult.Prepared(
                handle = handle,
                displayEndpoint = "https://example.com",
            ),
            cancellationResult = SourceCancellationResult.CleanupPending,
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
        assertThat(onboarding.cancelledHandles).containsExactly(handle)
    }

    @Test
    fun preparationFailureMapsWithoutImplementationExceptions() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResult = SourcePreparationResult.Failed(SourcePreparationFailure.StorageUnavailable),
        )
        val session = SourceEntrySession(onboarding)

        session.prepare("https://example.com/list.m3u")

        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Failed(SourceEntryFailure.StorageUnavailable),
        )
    }
}

private data class PrepareInput(
    val locator: String,
    val insecureHttpApproved: Boolean,
)

private class TestPreparationHandle(
    val index: Int,
) : SourcePreparationHandle()

private class FakeSourceEntryOnboarding(
    private val prepareResult: SourcePreparationResult =
        SourcePreparationResult.Failed(SourcePreparationFailure.InvalidLocator),
    private val prepareResults: ArrayDeque<SourcePreparationResult> = ArrayDeque(),
    private val restored: SourcePreparationResult.Prepared? = null,
    private val activationResult: SourceActivationResult = SourceActivationResult.Failed(
        reason = app.muxtv.catalog.SourceActivationFailure.Unexpected,
        cleanupPending = false,
    ),
    private val cancellationResult: SourceCancellationResult = SourceCancellationResult.Removed,
) : SourceOnboarding {
    val prepareInputs = mutableListOf<PrepareInput>()
    val activatedSourceNames = mutableListOf<String>()
    val cancelledHandles = mutableListOf<SourcePreparationHandle>()

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult {
        prepareInputs += PrepareInput(locator, insecureHttpApproved)
        return if (prepareResults.isEmpty()) prepareResult else prepareResults.removeFirst()
    }

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult {
        activatedSourceNames += sourceName
        return activationResult
    }

    override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
        cancelledHandles += handle
        return cancellationResult
    }

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = restored
}

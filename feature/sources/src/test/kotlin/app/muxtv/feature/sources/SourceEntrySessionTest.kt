package app.muxtv.feature.sources

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationFailure
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
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
    fun xtreamPreparationUsesProviderNeutralRequest() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResult = SourcePreparationResult.Prepared(
                handle = TestPreparationHandle(6),
                displayEndpoint = "https://provider.example",
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.prepareXtream(
            endpoint = "https://provider.example",
            username = "alice",
            password = "secret",
        )

        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("https://provider.example"),
        )
        assertThat(onboarding.preparationRequests).hasSize(1)
        val request = onboarding.preparationRequests.single() as SourcePreparationRequest.Xtream
        assertThat(request.endpoint).isEqualTo("https://provider.example")
        assertThat(request.username).isEqualTo("alice")
        assertThat(request.password).isEqualTo("secret")
        assertThat(request.insecureHttpApproved).isFalse()
        assertThat(request.toString()).doesNotContain("alice")
        assertThat(request.toString()).doesNotContain("secret")
    }

    @Test
    fun xtreamHttpApprovalRepeatsSameProviderRequestWithApproval() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResults = ArrayDeque(
                listOf(
                    SourcePreparationResult.InsecureTransportApprovalRequired,
                    SourcePreparationResult.Prepared(
                        handle = TestPreparationHandle(7),
                        displayEndpoint = "http://provider.example",
                    ),
                ),
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.prepareXtream(
            endpoint = "http://provider.example",
            username = "alice",
            password = "secret",
        )
        assertThat(session.state.value).isEqualTo(SourceEntryUiState.HttpApprovalRequired)

        session.approveInsecureHttp()

        assertThat(onboarding.preparationRequests).hasSize(2)
        val first = onboarding.preparationRequests[0] as SourcePreparationRequest.Xtream
        val approved = onboarding.preparationRequests[1] as SourcePreparationRequest.Xtream
        assertThat(first.insecureHttpApproved).isFalse()
        assertThat(approved.insecureHttpApproved).isTrue()
        assertThat(approved.endpoint).isEqualTo(first.endpoint)
        assertThat(approved.username).isEqualTo(first.username)
        assertThat(approved.password).isEqualTo(first.password)
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("http://provider.example"),
        )
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
    fun `local network permission is a dedicated state separate from HTTP approval`() {
        val stateNames = SourceEntryUiState::class.java.declaredClasses.map { it.simpleName }

        assertThat(stateNames).contains("LocalNetworkPermissionRequired")
        assertThat(stateNames).contains("LocalNetworkPermissionDenied")
    }

    @Test
    fun `local network grant replays the exact xtream request before HTTP approval`() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResults = ArrayDeque(
                listOf(
                    SourcePreparationResult.LocalNetworkAccessRequired,
                    SourcePreparationResult.InsecureTransportApprovalRequired,
                    SourcePreparationResult.Prepared(
                        handle = TestPreparationHandle(8),
                        displayEndpoint = "http://192.168.1.20:8080",
                    ),
                ),
            ),
        )
        val session = SourceEntrySession(onboarding)

        session.prepareXtream(
            endpoint = "http://192.168.1.20:8080/player_api.php?ignored=true",
            username = "alice",
            password = "secret",
        )

        assertThat(session.state.value).isEqualTo(SourceEntryUiState.LocalNetworkPermissionRequired)
        assertThat(onboarding.preparationRequests).hasSize(1)
        val initial = onboarding.preparationRequests.single() as SourcePreparationRequest.Xtream
        assertThat(initial.insecureHttpApproved).isFalse()

        session.resumeAfterLocalNetworkPermissionGranted()

        assertThat(session.state.value).isEqualTo(SourceEntryUiState.HttpApprovalRequired)
        assertThat(onboarding.preparationRequests).hasSize(2)
        val replayed = onboarding.preparationRequests[1] as SourcePreparationRequest.Xtream
        assertThat(replayed.endpoint).isEqualTo(initial.endpoint)
        assertThat(replayed.username).isEqualTo(initial.username)
        assertThat(replayed.password).isEqualTo(initial.password)
        assertThat(replayed.insecureHttpApproved).isFalse()

        session.approveInsecureHttp()

        assertThat(onboarding.preparationRequests).hasSize(3)
        val httpApproved = onboarding.preparationRequests[2] as SourcePreparationRequest.Xtream
        assertThat(httpApproved.endpoint).isEqualTo(initial.endpoint)
        assertThat(httpApproved.username).isEqualTo(initial.username)
        assertThat(httpApproved.password).isEqualTo(initial.password)
        assertThat(httpApproved.insecureHttpApproved).isTrue()
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("http://192.168.1.20:8080"),
        )
    }

    @Test
    fun `local network denial is typed and never retries preparation`() = runTest {
        val onboarding = FakeSourceEntryOnboarding(
            prepareResult = SourcePreparationResult.LocalNetworkAccessRequired,
        )
        val session = SourceEntrySession(onboarding)

        session.prepare("https://192.168.1.30/list.m3u")
        assertThat(session.state.value).isEqualTo(SourceEntryUiState.LocalNetworkPermissionRequired)

        session.recordLocalNetworkPermissionDenied(permanently = true)

        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.LocalNetworkPermissionDenied(permanently = true),
        )
        assertThat(onboarding.prepareInputs).hasSize(1)
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
    val preparationRequests = mutableListOf<SourcePreparationRequest>()
    val activatedSourceNames = mutableListOf<String>()
    val cancelledHandles = mutableListOf<SourcePreparationHandle>()

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult {
        prepareInputs += PrepareInput(locator, insecureHttpApproved)
        return nextPrepareResult()
    }

    override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
        preparationRequests += request
        return nextPrepareResult()
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

    private fun nextPrepareResult(): SourcePreparationResult =
        if (prepareResults.isEmpty()) prepareResult else prepareResults.removeFirst()
}

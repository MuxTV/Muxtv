package app.muxtv.feature.sources

import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourcePreparationGateContractTest {
    @Test
    fun deniedGatePreventsM3uPreparationBeforeNetworkOwnerIsCalled() = runTest {
        val onboarding = RecordingGateOnboarding()
        val gate = RecordingPreparationGate(SourcePreparationGateResult.Denied)
        val session = SourceEntrySession(onboarding, preparationGate = gate)

        session.prepare("http://192.168.1.20/list.m3u")

        assertThat(gate.requests).hasSize(1)
        assertThat(onboarding.requests).isEmpty()
        assertThat(onboarding.legacyPrepareCalls).isEqualTo(0)
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Failed(SourceEntryFailure.LocalNetworkPermissionDenied),
        )
    }

    @Test
    fun permanentDenialHasDistinctUserRecoverableStateAndStillBlocksPreparation() = runTest {
        val onboarding = RecordingGateOnboarding()
        val gate = RecordingPreparationGate(SourcePreparationGateResult.PermanentlyDenied)
        val session = SourceEntrySession(onboarding, preparationGate = gate)

        session.prepareXtream(
            endpoint = "http://192.168.1.21:8080",
            username = "alice",
            password = "secret",
        )

        assertThat(gate.requests).hasSize(1)
        assertThat(onboarding.requests).isEmpty()
        assertThat(onboarding.legacyPrepareCalls).isEqualTo(0)
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Failed(SourceEntryFailure.LocalNetworkPermissionPermanentlyDenied),
        )
        assertThat(gate.requests.single().toString()).doesNotContain("alice")
        assertThat(gate.requests.single().toString()).doesNotContain("secret")
    }

    @Test
    fun allowedXtreamRequestReachesExistingOnboardingUnchanged() = runTest {
        val onboarding = RecordingGateOnboarding(
            prepared = SourcePreparationResult.Prepared(
                handle = GatePreparationHandle(),
                displayEndpoint = "https://provider.example",
            ),
        )
        val gate = RecordingPreparationGate(SourcePreparationGateResult.Allowed)
        val session = SourceEntrySession(onboarding, preparationGate = gate)

        session.prepareXtream(
            endpoint = "https://provider.example",
            username = "alice",
            password = "secret",
        )

        assertThat(gate.requests).hasSize(1)
        assertThat(onboarding.requests).hasSize(1)
        assertThat(onboarding.requests.single()).isEqualTo(gate.requests.single())
        assertThat(session.state.value).isEqualTo(
            SourceEntryUiState.Confirming("https://provider.example"),
        )
    }
}

private class RecordingPreparationGate(
    private val result: SourcePreparationGateResult,
) : SourcePreparationGate {
    val requests = mutableListOf<SourcePreparationRequest>()

    override suspend fun evaluate(request: SourcePreparationRequest): SourcePreparationGateResult {
        requests += request
        return result
    }
}

private class GatePreparationHandle : SourcePreparationHandle()

private class RecordingGateOnboarding(
    private val prepared: SourcePreparationResult = SourcePreparationResult.Failed(
        app.muxtv.catalog.SourcePreparationFailure.InvalidLocator,
    ),
) : SourceOnboarding {
    val requests = mutableListOf<SourcePreparationRequest>()
    var legacyPrepareCalls = 0

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult {
        legacyPrepareCalls += 1
        return prepared
    }

    override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
        requests += request
        return prepared
    }

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult = SourceActivationResult.Failed(
        reason = SourceActivationFailure.Unexpected,
        cleanupPending = false,
    )

    override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult =
        SourceCancellationResult.Removed

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
}

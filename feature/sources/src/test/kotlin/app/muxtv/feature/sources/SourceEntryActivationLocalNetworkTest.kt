package app.muxtv.feature.sources

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceEntryActivationLocalNetworkTest {
    @Test
    fun `activation LAN barrier keeps prepared handle and replays same activation after grant`() = runTest {
        val handle = ActivationTestHandle()
        val onboarding = ActivationLanOnboarding(handle)
        val session = SourceEntrySession(onboarding)

        session.prepare("https://192.168.1.50/list.m3u")
        session.activate("Primary")

        assertThat(session.state.value).isEqualTo(SourceEntryUiState.LocalNetworkPermissionRequired)
        assertThat(onboarding.activationNames).containsExactly("Primary")
        assertThat(onboarding.cancelCalls).isEqualTo(0)

        session.resumeAfterLocalNetworkPermissionGranted()

        assertThat(session.state.value).isEqualTo(SourceEntryUiState.Completed)
        assertThat(onboarding.activationNames).containsExactly("Primary", "Primary").inOrder()
        assertThat(onboarding.cancelCalls).isEqualTo(0)
    }
}

private class ActivationTestHandle : SourcePreparationHandle()

private class ActivationLanOnboarding(
    private val handle: SourcePreparationHandle,
) : SourceOnboarding {
    val activationNames = mutableListOf<String>()
    var cancelCalls = 0
    private var activationCount = 0

    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = SourcePreparationResult.Prepared(
        handle = handle,
        displayEndpoint = "https://192.168.1.50",
    )

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult {
        check(handle === this.handle)
        activationNames += sourceName
        activationCount += 1
        return if (activationCount == 1) {
            SourceActivationResult.LocalNetworkAccessRequired
        } else {
            SourceActivationResult.Activated
        }
    }

    override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
        cancelCalls += 1
        return SourceCancellationResult.Removed
    }

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
}

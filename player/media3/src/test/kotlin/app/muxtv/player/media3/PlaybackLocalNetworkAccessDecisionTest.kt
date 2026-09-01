package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.player.PlaybackStartResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackLocalNetworkAccessDecisionTest {
    @Test
    fun readyResolutionUsesFinalLocatorAndReturnsVariantOnly() {
        val candidate = candidate()
        val target = "https://192.168.1.20/live.m3u8?token=secret"
        val observedTargets = mutableListOf<String>()

        val result = PlaybackLocalNetworkAccessDecision.requiredResult(
            candidate = candidate,
            resolution = PlaybackVariantResolution.Ready(
                ResolvedPlaybackRequest(
                    channelId = candidate.channelId,
                    variantId = candidate.variantId,
                    locator = target,
                    requestHeaders = mapOf("Authorization" to "Bearer secret"),
                    insecureHttpApproved = true,
                ),
            ),
            gate = PlaybackLocalNetworkAccessGate { value ->
                observedTargets += value
                true
            },
        )

        assertThat(result).isEqualTo(
            PlaybackStartResult.LocalNetworkPermissionRequired(candidate.variantId),
        )
        assertThat(observedTargets).containsExactly(target)
        assertThat(result.toString()).doesNotContain(target)
        assertThat(result.toString()).doesNotContain("secret")
    }

    @Test
    fun insecureHttpApprovalUsesResolvedDisplayOriginBeforeHttpApproval() {
        val candidate = candidate()
        val displayOrigin = "http://192.168.1.20:8080"
        val observedTargets = mutableListOf<String>()

        val result = PlaybackLocalNetworkAccessDecision.requiredResult(
            candidate = candidate,
            resolution = PlaybackVariantResolution.InsecureTransportApprovalRequired(
                channelId = candidate.channelId,
                variantId = candidate.variantId,
                displayOrigin = displayOrigin,
            ),
            gate = PlaybackLocalNetworkAccessGate { value ->
                observedTargets += value
                true
            },
        )

        assertThat(result).isEqualTo(
            PlaybackStartResult.LocalNetworkPermissionRequired(candidate.variantId),
        )
        assertThat(observedTargets).containsExactly(displayOrigin)
    }

    @Test
    fun gatePassThroughLeavesExistingResolutionFlowUntouched() {
        val candidate = candidate()

        val result = PlaybackLocalNetworkAccessDecision.requiredResult(
            candidate = candidate,
            resolution = ready(candidate, "https://cdn.example/live.m3u8"),
            gate = PlaybackLocalNetworkAccessGate { false },
        )

        assertThat(result).isNull()
    }

    @Test
    fun mismatchedResolutionFailsClosedBeforeTargetInspection() {
        val candidate = candidate()
        var gateCalls = 0

        val result = PlaybackLocalNetworkAccessDecision.requiredResult(
            candidate = candidate,
            resolution = PlaybackVariantResolution.Ready(
                ResolvedPlaybackRequest(
                    channelId = candidate.channelId,
                    variantId = "variant-other",
                    locator = "http://192.168.1.20/other.m3u8",
                    requestHeaders = emptyMap(),
                    insecureHttpApproved = true,
                ),
            ),
            gate = PlaybackLocalNetworkAccessGate {
                gateCalls += 1
                true
            },
        )

        assertThat(result).isNull()
        assertThat(gateCalls).isEqualTo(0)
    }

    @Test
    fun unavailableResolutionNeverRequestsPlatformAccess() {
        val candidate = candidate()
        var gateCalls = 0

        val result = PlaybackLocalNetworkAccessDecision.requiredResult(
            candidate = candidate,
            resolution = PlaybackVariantResolution.AccessUnavailable(
                PlaybackAccessUnavailableReason.CredentialUnavailable,
            ),
            gate = PlaybackLocalNetworkAccessGate {
                gateCalls += 1
                true
            },
        )

        assertThat(result).isNull()
        assertThat(gateCalls).isEqualTo(0)
    }

    private fun candidate() = PlaybackCandidateIdentity(
        channelId = "channel-local",
        variantId = "variant-local",
    )

    private fun ready(
        candidate: PlaybackCandidateIdentity,
        locator: String,
    ) = PlaybackVariantResolution.Ready(
        ResolvedPlaybackRequest(
            channelId = candidate.channelId,
            variantId = candidate.variantId,
            locator = locator,
            requestHeaders = emptyMap(),
            insecureHttpApproved = true,
        ),
    )
}

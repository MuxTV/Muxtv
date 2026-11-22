package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderNeutralSourcePreparationContractTest {
    @Test
    fun providerNeutralRequestsRedactAllUserSuppliedAccessMaterial() {
        val m3u = SourcePreparationRequest.M3u(
            locator = "https://playlist.example/live.m3u?token=m3u-secret",
            insecureHttpApproved = false,
        )
        val xtream = SourcePreparationRequest.Xtream(
            endpoint = "https://xtream.example/customer/path?tenant=endpoint-secret",
            username = "xtream-user-secret",
            password = "xtream-password-secret",
            insecureHttpApproved = true,
        )

        assertThat(m3u.toString()).doesNotContain("playlist.example")
        assertThat(m3u.toString()).doesNotContain("m3u-secret")
        assertThat(m3u.toString()).contains("insecureHttpApproved=false")

        val renderedXtream = xtream.toString()
        assertThat(renderedXtream).doesNotContain("xtream.example")
        assertThat(renderedXtream).doesNotContain("endpoint-secret")
        assertThat(renderedXtream).doesNotContain("xtream-user-secret")
        assertThat(renderedXtream).doesNotContain("xtream-password-secret")
        assertThat(renderedXtream).contains("insecureHttpApproved=true")
    }

    @Test
    fun providerNeutralPrepareKeepsLegacyM3uImplementationsSourceCompatible() {
        val onboarding = LegacyM3uOnlyOnboarding()

        val result = kotlinx.coroutines.runBlocking {
            onboarding.prepare(
                SourcePreparationRequest.M3u(
                    locator = "https://playlist.example/live.m3u",
                    insecureHttpApproved = true,
                ),
            )
        }

        assertThat(result).isEqualTo(SourcePreparationResult.InsecureTransportApprovalRequired)
        assertThat(onboarding.lastLocator).isEqualTo("https://playlist.example/live.m3u")
        assertThat(onboarding.lastInsecureHttpApproved).isTrue()
    }

    @Test
    fun legacyImplementationRejectsXtreamAsTypedUnsupportedProvider() {
        val onboarding = LegacyM3uOnlyOnboarding()

        val result = kotlinx.coroutines.runBlocking {
            onboarding.prepare(
                SourcePreparationRequest.Xtream(
                    endpoint = "https://xtream.example",
                    username = "user-secret",
                    password = "password-secret",
                ),
            )
        }

        assertThat(result).isEqualTo(
            SourcePreparationResult.Failed(SourcePreparationFailure.UnsupportedProvider),
        )
        assertThat(onboarding.lastLocator).isNull()
    }

    private class LegacyM3uOnlyOnboarding : SourceOnboarding {
        var lastLocator: String? = null
        var lastInsecureHttpApproved: Boolean = false

        override suspend fun prepare(
            locator: String,
            insecureHttpApproved: Boolean,
        ): SourcePreparationResult {
            lastLocator = locator
            lastInsecureHttpApproved = insecureHttpApproved
            return SourcePreparationResult.InsecureTransportApprovalRequired
        }

        override suspend fun activate(
            handle: SourcePreparationHandle,
            sourceName: String,
        ): SourceActivationResult = SourceActivationResult.Activated

        override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult =
            SourceCancellationResult.NotFound

        override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
    }
}

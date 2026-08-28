package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourcePortContractTest {
    @Test
    fun preparationHandleNeverRendersImplementationIdentity() {
        val handle = TestHandle("credential-backed-secret")

        assertThat(handle.toString()).isEqualTo("SourcePreparationHandle(<redacted>)")
        assertThat(handle.toString()).doesNotContain("credential-backed-secret")
    }

    @Test
    fun preparedResultAcceptsOnlySanitizedDisplayEndpointShape() {
        val prepared = SourcePreparationResult.Prepared(
            handle = TestHandle("opaque"),
            displayEndpoint = "https://provider.example",
        )

        assertThat(prepared.displayEndpoint).isEqualTo("https://provider.example")
        assertThat(prepared.toString()).doesNotContain("opaque")
    }

    @Test
    fun refreshPolicyRetainsUiSchedulingSemanticsWithoutImplementationIdentity() {
        val policy = SourceRefreshPolicy(
            sourceId = "source-1",
            enabled = true,
            intervalMinutes = 60L,
            unmeteredOnly = true,
            requiresCharging = false,
            updatedAtEpochMillis = 42L,
        )

        assertThat(policy.intervalMinutes).isEqualTo(60L)
        assertThat(policy.unmeteredOnly).isTrue()
    }

    private class TestHandle(
        @Suppress("unused") private val implementationIdentity: String,
    ) : SourcePreparationHandle()
}

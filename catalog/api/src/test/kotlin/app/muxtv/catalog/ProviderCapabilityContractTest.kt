package app.muxtv.catalog

import app.muxtv.common.SourceId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderCapabilityContractTest {
    @Test
    fun descriptorBindsNeutralCapabilitiesToTypedSource() {
        val sourceId = SourceId("m3u-source")
        val descriptor = ProviderDescriptor(
            sourceId = sourceId,
            capabilities = setOf(
                ProviderCapability.LIVE,
                ProviderCapability.EPG,
                ProviderCapability.CATCHUP,
            ),
        )

        assertThat(descriptor.sourceId).isEqualTo(sourceId)
        assertThat(descriptor.capabilities).containsExactly(
            ProviderCapability.LIVE,
            ProviderCapability.EPG,
            ProviderCapability.CATCHUP,
        )
    }

    @Test
    fun descriptorSnapshotsMutableCapabilityInput() {
        val mutableCapabilities = linkedSetOf(
            ProviderCapability.LIVE,
            ProviderCapability.EPG,
        )
        val descriptor = ProviderDescriptor(
            sourceId = SourceId("m3u-source"),
            capabilities = mutableCapabilities,
        )

        mutableCapabilities.clear()
        mutableCapabilities += ProviderCapability.CATCHUP

        assertThat(descriptor.capabilities).containsExactly(
            ProviderCapability.LIVE,
            ProviderCapability.EPG,
        )
    }

    @Test
    fun capabilityDoesNotChangeReadiness() {
        val descriptor = ProviderDescriptor(
            sourceId = SourceId("m3u-source"),
            capabilities = setOf(ProviderCapability.LIVE),
        )
        val readiness = ProviderReadinessSnapshot(
            sourceId = descriptor.sourceId,
            activeCatalog = null,
        )

        assertThat(descriptor.capabilities).contains(ProviderCapability.LIVE)
        assertThat(readiness.usability).isEqualTo(ProviderUsability.NOT_USABLE)
    }

    @Test
    fun descriptorDiagnosticsAreIdentitySafeAndDeterministic() {
        val first = ProviderDescriptor(
            sourceId = SourceId("source-secret-one"),
            capabilities = linkedSetOf(
                ProviderCapability.CATCHUP,
                ProviderCapability.LIVE,
            ),
        )
        val second = ProviderDescriptor(
            sourceId = SourceId("source-secret-two"),
            capabilities = linkedSetOf(
                ProviderCapability.LIVE,
                ProviderCapability.CATCHUP,
            ),
        )

        assertThat(first.toString()).doesNotContain("source-secret-one")
        assertThat(second.toString()).doesNotContain("source-secret-two")
        assertThat(first.toString()).isEqualTo(second.toString())
        assertThat(first.toString()).contains("capabilities=[LIVE, CATCHUP]")
    }

    @Test
    fun m3uPathFitsProviderNeutralVocabularyWithoutXtreamFields() {
        val descriptor = ProviderDescriptor(
            sourceId = SourceId("m3u-source"),
            capabilities = setOf(
                ProviderCapability.LIVE,
                ProviderCapability.EPG,
                ProviderCapability.CATCHUP,
            ),
        )

        assertThat(descriptor.capabilities).containsExactly(
            ProviderCapability.LIVE,
            ProviderCapability.EPG,
            ProviderCapability.CATCHUP,
        )
        assertThat(descriptor.toString()).doesNotContain("Xtream")
    }
}

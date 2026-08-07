package app.muxtv.catalog

import app.muxtv.common.SourceId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderReadinessInvariantTest {
    @Test
    fun `secondary attempt exposes terminal cancelled and superseded states`() {
        val terminalStates: List<ProviderSecondaryAttempt> = listOf(
            ProviderSecondaryAttempt.Cancelled,
            ProviderSecondaryAttempt.Superseded,
        )

        assertThat(terminalStates).hasSize(2)
    }

    @Test
    fun `successful secondary attempt requires matching active revision`() {
        val missingActive = runCatching {
            ProviderSecondaryState(
                activeRevisionNumber = null,
                latestAttempt = ProviderSecondaryAttempt.Succeeded(revisionNumber = 7),
            )
        }.exceptionOrNull()
        val mismatchedActive = runCatching {
            ProviderSecondaryState(
                activeRevisionNumber = 6,
                latestAttempt = ProviderSecondaryAttempt.Succeeded(revisionNumber = 7),
            )
        }.exceptionOrNull()

        assertThat(missingActive).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(mismatchedActive).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `successful catalog attempt requires matching active catalog revision`() {
        val noActiveCatalog = runCatching {
            ProviderReadinessSnapshot(
                sourceId = SourceId("source-redacted"),
                activeCatalog = null,
                latestCatalogAttempt = ProviderCatalogSyncAttempt.Succeeded(revisionNumber = 5),
            )
        }.exceptionOrNull()
        val mismatchedCatalog = runCatching {
            ProviderReadinessSnapshot(
                sourceId = SourceId("source-redacted"),
                activeCatalog = ProviderActiveCatalog(
                    revisionNumber = 4,
                    channelCount = 10,
                    activatedAtEpochMillis = 100,
                ),
                latestCatalogAttempt = ProviderCatalogSyncAttempt.Succeeded(revisionNumber = 5),
            )
        }.exceptionOrNull()

        assertThat(noActiveCatalog).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(mismatchedCatalog).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `matching successful revisions remain valid`() {
        val epg = ProviderSecondaryState(
            activeRevisionNumber = 7,
            latestAttempt = ProviderSecondaryAttempt.Succeeded(revisionNumber = 7),
        )
        val snapshot = ProviderReadinessSnapshot(
            sourceId = SourceId("source-redacted"),
            activeCatalog = ProviderActiveCatalog(
                revisionNumber = 5,
                channelCount = 10,
                activatedAtEpochMillis = 100,
            ),
            latestCatalogAttempt = ProviderCatalogSyncAttempt.Succeeded(revisionNumber = 5),
            epg = epg,
        )

        assertThat(snapshot.usability).isEqualTo(ProviderUsability.USABLE)
        assertThat(snapshot.epg.hasActiveData).isTrue()
    }
}

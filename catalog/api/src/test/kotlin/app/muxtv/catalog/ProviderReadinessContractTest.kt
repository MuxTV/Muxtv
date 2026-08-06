package app.muxtv.catalog

import app.muxtv.common.SourceId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderReadinessContractTest {
    @Test
    fun activeCatalogMakesProviderUsableWhileEpgIsPending() {
        val snapshot = ProviderReadinessSnapshot(
            sourceId = SourceId("source-secret"),
            activeCatalog = ProviderActiveCatalog(
                revisionNumber = 4,
                channelCount = 120,
                activatedAtEpochMillis = 1_000L,
            ),
            latestCatalogAttempt = ProviderCatalogSyncAttempt.Idle,
            epg = ProviderSecondaryState(
                latestAttempt = ProviderSecondaryAttempt.Running,
            ),
        )

        assertThat(snapshot.usability).isEqualTo(ProviderUsability.USABLE)
    }

    @Test
    fun epgFailureCannotDowngradeUsableProvider() {
        val snapshot = usableSnapshot(
            epg = ProviderSecondaryState(
                latestAttempt = ProviderSecondaryAttempt.Failed(
                    ProviderSyncFailure.Timeout,
                ),
            ),
        )

        assertThat(snapshot.usability).isEqualTo(ProviderUsability.USABLE)
        assertThat(snapshot.epg.hasActiveData).isFalse()
    }

    @Test
    fun previousGoodEpgSurvivesLaterFailedAttempt() {
        val epg = ProviderSecondaryState(
            activeRevisionNumber = 7,
            latestAttempt = ProviderSecondaryAttempt.Failed(
                ProviderSyncFailure.Network,
            ),
        )

        assertThat(epg.activeRevisionNumber).isEqualTo(7)
        assertThat(epg.hasActiveData).isTrue()
    }

    @Test
    fun failedLiveRefreshPreservesPreviousGoodCatalog() {
        val snapshot = usableSnapshot(
            latestCatalogAttempt = ProviderCatalogSyncAttempt.Failed(
                ProviderSyncFailure.RateLimited(retryAfterEpochMillis = 9_000L),
            ),
        )

        assertThat(snapshot.usability).isEqualTo(ProviderUsability.USABLE)
        assertThat(snapshot.activeCatalog?.revisionNumber).isEqualTo(4)
        assertThat(snapshot.activeCatalog?.channelCount).isEqualTo(120)
    }

    @Test
    fun epgReadyWithoutActiveCatalogDoesNotMakeProviderUsable() {
        val snapshot = ProviderReadinessSnapshot(
            sourceId = SourceId("source-secret"),
            activeCatalog = null,
            latestCatalogAttempt = ProviderCatalogSyncAttempt.Failed(
                ProviderSyncFailure.AuthenticationRequired,
            ),
            epg = ProviderSecondaryState(
                activeRevisionNumber = 3,
                latestAttempt = ProviderSecondaryAttempt.Succeeded(revisionNumber = 3),
            ),
        )

        assertThat(snapshot.usability).isEqualTo(ProviderUsability.NOT_USABLE)
        assertThat(snapshot.epg.hasActiveData).isTrue()
    }

    @Test
    fun authenticationRateLimitTimeoutAndNetworkRemainDistinctFailures() {
        val failures = listOf(
            ProviderSyncFailure.AuthenticationRequired,
            ProviderSyncFailure.RateLimited(retryAfterEpochMillis = null),
            ProviderSyncFailure.Timeout,
            ProviderSyncFailure.Network,
        )

        assertThat(failures.map { it::class }).containsExactly(
            ProviderSyncFailure.AuthenticationRequired::class,
            ProviderSyncFailure.RateLimited::class,
            ProviderSyncFailure.Timeout::class,
            ProviderSyncFailure.Network::class,
        ).inOrder()
    }

    @Test
    fun rateLimitRejectsNegativeRetryMetadata() {
        val error = runCatching {
            ProviderSyncFailure.RateLimited(retryAfterEpochMillis = -1L)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun progressRejectsNegativeCompletedWork() {
        listOf(
            -1 to 0,
            0 to -1,
        ).forEach { (pages, items) ->
            val error = runCatching {
                ProviderSyncProgress(
                    completedPages = pages,
                    discoveredItems = items,
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun progressDiagnosticsContainOnlyCompletedWorkAndNoProviderTotal() {
        val diagnostic = ProviderSyncProgress(
            completedPages = 3,
            discoveredItems = 240,
        ).toString()

        assertThat(diagnostic).contains("completedPages=3")
        assertThat(diagnostic).contains("discoveredItems=240")
        assertThat(diagnostic).doesNotContain("total")
        assertThat(diagnostic).doesNotContain("percent")
    }

    @Test
    fun snapshotDiagnosticsRedactSourceIdentity() {
        val diagnostic = usableSnapshot().toString()

        assertThat(diagnostic).doesNotContain("source-secret")
        assertThat(diagnostic).contains("usability=USABLE")
        assertThat(diagnostic).contains("activeCatalogRevision=4")
        assertThat(diagnostic).contains("activeChannelCount=120")
    }

    @Test
    fun valueObjectsRejectInvalidRevisionAndCountValues() {
        val failures = listOf(
            runCatching {
                ProviderActiveCatalog(
                    revisionNumber = 0,
                    channelCount = 1,
                    activatedAtEpochMillis = 0,
                )
            }.exceptionOrNull(),
            runCatching {
                ProviderActiveCatalog(
                    revisionNumber = 1,
                    channelCount = 0,
                    activatedAtEpochMillis = 0,
                )
            }.exceptionOrNull(),
            runCatching {
                ProviderSecondaryState(activeRevisionNumber = 0)
            }.exceptionOrNull(),
            runCatching {
                ProviderCatalogSyncAttempt.Succeeded(revisionNumber = 0)
            }.exceptionOrNull(),
            runCatching {
                ProviderSecondaryAttempt.Succeeded(revisionNumber = 0)
            }.exceptionOrNull(),
        )

        assertThat(failures).allMatch { it is IllegalArgumentException }
    }

    private fun usableSnapshot(
        latestCatalogAttempt: ProviderCatalogSyncAttempt = ProviderCatalogSyncAttempt.Idle,
        epg: ProviderSecondaryState = ProviderSecondaryState(),
    ): ProviderReadinessSnapshot = ProviderReadinessSnapshot(
        sourceId = SourceId("source-secret"),
        activeCatalog = ProviderActiveCatalog(
            revisionNumber = 4,
            channelCount = 120,
            activatedAtEpochMillis = 1_000L,
        ),
        latestCatalogAttempt = latestCatalogAttempt,
        epg = epg,
    )
}

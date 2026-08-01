package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EpgMatchingFanoutYagniUnitTest {
    @Test
    fun providerReconcileProcessesEveryLinkedGuideWithoutSpeculativeHardCap() = runBlocking {
        val store = RoomEpgMatchingStore(FakeMatchingDao(linkedGuideCount = LINKED_GUIDE_COUNT))

        val result = store.reconcileProviderSource(PROVIDER_SOURCE)

        assertThat(result).isEqualTo(
            EpgProviderMatchingReconcileResult.Applied(
                processedCount = LINKED_GUIDE_COUNT,
                appliedCount = LINKED_GUIDE_COUNT,
                notReadyCount = 0,
                supersededCount = 0,
            ),
        )
    }

    private class FakeMatchingDao(
        linkedGuideCount: Int,
    ) : EpgMatchingDao() {
        private val linkedIds = List(linkedGuideCount) { index -> "epg-$index" }

        override suspend fun relationProjection(epgSourceId: String): RelationProjection =
            RelationProjection(
                epgSourceId = epgSourceId,
                epgRevisionNumber = 1,
                providerSourceId = PROVIDER_SOURCE,
                catalogRevisionNumber = 1,
            )

        override suspend fun linkedActiveEpgSourceIds(
            providerSourceId: String,
            limit: Int,
        ): List<String> {
            require(providerSourceId == PROVIDER_SOURCE)
            return linkedIds.take(limit)
        }

        override suspend fun epgChannels(
            epgSourceId: String,
            epgRevisionNumber: Long,
        ): List<EpgMatchInputChannel> = emptyList()

        override suspend fun providerIdEvidence(
            providerSourceId: String,
            catalogRevisionNumber: Long,
        ): List<EpgMatchEvidenceRow> = emptyList()

        override suspend fun providerTvgNameEvidence(
            providerSourceId: String,
            catalogRevisionNumber: Long,
        ): List<EpgMatchEvidenceRow> = emptyList()

        override suspend fun providerRawNameEvidence(
            providerSourceId: String,
            catalogRevisionNumber: Long,
        ): List<EpgMatchEvidenceRow> = emptyList()

        override suspend fun deleteMatchesForEpgSource(epgSourceId: String): Int = 0

        override suspend fun insertMatches(matches: List<EpgChannelMatchEntity>) = Unit

        override suspend fun matchesForEpgSource(
            epgSourceId: String,
        ): List<EpgChannelMatchEntity> = emptyList()
    }

    private companion object {
        const val PROVIDER_SOURCE = "provider-source"
        const val LINKED_GUIDE_COUNT = 33
    }
}

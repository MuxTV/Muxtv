package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgMatchingPolicyVersionTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: EpgMatchingStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomEpgMatchingStore(database.epgMatchingDao())
        insertProducerRelation()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reconcileIfStaleReturnsCurrentWithoutReplacingCurrentPolicyRows() = runTest {
        val first = store.reconcileIfStale(EPG_SOURCE)
        assertThat(first).isInstanceOf(EpgMatchingReconcileResult.Applied::class.java)
        val firstRows = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE)
        assertThat(firstRows).hasSize(1)
        assertThat(firstRows.single().matchPolicyVersion)
            .isEqualTo(CURRENT_EPG_MATCH_POLICY_VERSION)

        val second = store.reconcileIfStale(EPG_SOURCE)
        val secondRows = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE)

        assertThat(second).isEqualTo(EpgMatchingReconcileResult.Current)
        assertThat(secondRows).containsExactlyElementsIn(firstRows).inOrder()
    }

    @Test
    fun reconcileIfStaleRebuildsRowsFromLegacyPolicy() = runTest {
        val dao = database.epgMatchingDao()
        val snapshot = requireNotNull(dao.relationSnapshot(EPG_SOURCE))
        assertThat(
            dao.replaceIfCurrent(
                snapshot = snapshot,
                matches = listOf(
                    currentMatchEntity(matchPolicyVersion = LEGACY_UNVERSIONED_MATCH_POLICY_VERSION),
                ),
            ),
        ).isEqualTo(EpgMatchPublicationResult.Applied)

        val result = store.reconcileIfStale(EPG_SOURCE)
        val row = dao.matchesForEpgSource(EPG_SOURCE).single()

        assertThat(result).isInstanceOf(EpgMatchingReconcileResult.Applied::class.java)
        assertThat(row.matchPolicyVersion).isEqualTo(CURRENT_EPG_MATCH_POLICY_VERSION)
        assertThat(row.decision).isEqualTo(EpgChannelMatchDecision.MATCHED.name)
        assertThat(row.canonicalChannelId).isEqualTo(CANONICAL_ID)
    }

    private suspend fun insertProducerRelation() {
        database.sourceRevisionDao().insertSource(
            SourceEntity(
                id = SOURCE_ID,
                name = "Provider",
                activeRevision = 1,
            ),
        )
        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 1,
                activatedAtEpochMillis = 2,
                parsedEntries = 1,
            ),
        )
        database.sourceRevisionDao().insertCanonicalChannels(
            listOf(CanonicalChannelEntity(id = CANONICAL_ID, displayName = "News")),
        )
        database.sourceRevisionDao().insertProviderChannels(
            listOf(
                ProviderChannelEntity(
                    id = PROVIDER_CHANNEL_ID,
                    sourceId = SOURCE_ID,
                    revisionNumber = 1,
                    providerKey = "tvg:news.id",
                    rawName = "News",
                    tvgId = "news.id",
                    tvgName = "News",
                ),
            ),
        )
        database.sourceRevisionDao().insertStreamVariants(
            listOf(
                StreamVariantEntity(
                    id = "variant-news",
                    providerChannelId = PROVIDER_CHANNEL_ID,
                    canonicalChannelId = CANONICAL_ID,
                    locator = "https://example.invalid/news.m3u8",
                ),
            ),
        )

        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = EPG_SOURCE,
                name = "Guide",
                providerSourceId = SOURCE_ID,
                accessRef = null,
                defaultZoneId = "UTC",
                activeRevision = 1,
            ),
        )
        database.epgRevisionDao().insertRevision(
            EpgRevisionEntity(
                sourceId = EPG_SOURCE,
                revisionNumber = 1,
                status = EpgRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 1,
                activatedAtEpochMillis = 2,
                acceptedChannels = 1,
            ),
        )
        database.epgRevisionDao().insertChannels(
            listOf(
                EpgChannelEntity(
                    sourceId = EPG_SOURCE,
                    revisionNumber = 1,
                    externalId = "news.id",
                    primaryDisplayName = "News",
                    primaryLanguage = null,
                    iconRef = null,
                ),
            ),
        )
    }

    private fun currentMatchEntity(matchPolicyVersion: Int): EpgChannelMatchEntity =
        EpgChannelMatchEntity(
            epgSourceId = EPG_SOURCE,
            epgRevisionNumber = 1,
            providerSourceId = SOURCE_ID,
            catalogRevisionNumber = 1,
            epgExternalChannelId = "news.id",
            matchPolicyVersion = matchPolicyVersion,
            decision = EpgChannelMatchDecision.MATCHED.name,
            reasonCode = EpgMatchReasonCode.EXACT_ID.name,
            canonicalChannelId = CANONICAL_ID,
            candidateCount = 1,
        )

    private companion object {
        const val SOURCE_ID = "source-a"
        const val EPG_SOURCE = "epg-a"
        const val PROVIDER_CHANNEL_ID = "provider-news"
        const val CANONICAL_ID = "canonical-news"
    }
}

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
class EpgMatchingStoreTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: EpgMatchingStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomEpgMatchingStore(database.epgMatchingDao())
        insertCatalogSource(SOURCE_A, activeRevision = 1)
        insertEpgSource(EPG_SOURCE, providerSourceId = SOURCE_A, activeRevision = 1)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactIdWinsOverAmbiguousDisplayNameEvidence() = runTest {
        insertProviderChannel(
            id = "provider-1",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-1",
            rawName = "News",
            tvgId = "news.id",
            tvgName = "News",
        )
        insertProviderChannel(
            id = "provider-2",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-2",
            rawName = "News",
            tvgId = "other.id",
            tvgName = "News",
        )
        insertEpgChannel(externalId = "news.id", displayName = "News")

        val result = store.reconcile(EPG_SOURCE)

        assertThat(result).isEqualTo(
            EpgMatchingReconcileResult.Applied(
                EpgMatchingSummary(
                    epgRevisionNumber = 1,
                    catalogRevisionNumber = 1,
                    matchedCount = 1,
                    ambiguousCount = 0,
                    unresolvedCount = 0,
                ),
            ),
        )
        assertThat(database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE))
            .containsExactly(
                EpgChannelMatchEntity(
                    epgSourceId = EPG_SOURCE,
                    epgRevisionNumber = 1,
                    providerSourceId = SOURCE_A,
                    catalogRevisionNumber = 1,
                    epgExternalChannelId = "news.id",
                    decision = EpgChannelMatchDecision.MATCHED.name,
                    reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                    canonicalChannelId = "canonical-1",
                    candidateCount = 1,
                ),
            )
    }

    @Test
    fun duplicateExactNamesAcrossDistinctCanonicalIdsAreAmbiguous() = runTest {
        insertProviderChannel(
            id = "provider-1",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-1",
            rawName = "Channel One",
            tvgId = "id-1",
            tvgName = "Shared News",
        )
        insertProviderChannel(
            id = "provider-2",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-2",
            rawName = "Channel Two",
            tvgId = "id-2",
            tvgName = "Shared News",
        )
        insertEpgChannel(externalId = "missing-id", displayName = " shared\u00A0 NEWS ")

        store.reconcile(EPG_SOURCE)

        val row = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE).single()
        assertThat(row.decision).isEqualTo(EpgChannelMatchDecision.AMBIGUOUS.name)
        assertThat(row.reasonCode).isEqualTo(EpgMatchReasonCode.EXACT_TVG_NAME.name)
        assertThat(row.canonicalChannelId).isNull()
        assertThat(row.candidateCount).isEqualTo(2)
    }

    @Test
    fun sameNameInAnotherProviderSourceCannotMatch() = runTest {
        insertCatalogSource(SOURCE_B, activeRevision = 1)
        insertProviderChannel(
            id = "provider-b",
            sourceId = SOURCE_B,
            revisionNumber = 1,
            canonicalId = "canonical-b",
            rawName = "Only Elsewhere",
            tvgId = "elsewhere-id",
            tvgName = "Only Elsewhere",
        )
        insertEpgChannel(externalId = "missing-id", displayName = "Only Elsewhere")

        store.reconcile(EPG_SOURCE)

        val row = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE).single()
        assertThat(row.decision).isEqualTo(EpgChannelMatchDecision.UNRESOLVED.name)
        assertThat(row.reasonCode).isEqualTo(EpgMatchReasonCode.NO_MATCH.name)
        assertThat(row.canonicalChannelId).isNull()
        assertThat(row.candidateCount).isEqualTo(0)
    }

    @Test
    fun renamedDisplayTextStillMatchesByExactProviderId() = runTest {
        insertProviderChannel(
            id = "provider-1",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-1",
            rawName = "Completely Different Catalog Name",
            tvgId = "stable-id",
            tvgName = "Also Different",
        )
        insertEpgChannel(externalId = "stable-id", displayName = "Guide Rename")

        store.reconcile(EPG_SOURCE)

        val row = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE).single()
        assertThat(row.canonicalChannelId).isEqualTo("canonical-1")
        assertThat(row.reasonCode).isEqualTo(EpgMatchReasonCode.EXACT_ID.name)
    }

    @Test
    fun sameRevisionPairReconcileIsIdempotent() = runTest {
        insertProviderChannel(
            id = "provider-1",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-1",
            rawName = "News",
            tvgId = "news.id",
            tvgName = "News",
        )
        insertEpgChannel(externalId = "news.id", displayName = "News")

        val first = store.reconcile(EPG_SOURCE)
        val firstRows = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE)
        val second = store.reconcile(EPG_SOURCE)
        val secondRows = database.epgMatchingDao().matchesForEpgSource(EPG_SOURCE)

        assertThat(second).isEqualTo(first)
        assertThat(secondRows).containsExactlyElementsIn(firstRows).inOrder()
    }

    @Test
    fun replaceIfCurrentRejectsRowsWhenCatalogRevisionMovedAfterSnapshot() = runTest {
        insertProviderChannel(
            id = "provider-1",
            sourceId = SOURCE_A,
            revisionNumber = 1,
            canonicalId = "canonical-1",
            rawName = "News",
            tvgId = "news.id",
            tvgName = "News",
        )
        insertEpgChannel(externalId = "news.id", displayName = "News")
        val dao = database.epgMatchingDao()
        val snapshot = requireNotNull(dao.relationSnapshot(EPG_SOURCE))

        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = SOURCE_A,
                revisionNumber = 2,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 30,
                activatedAtEpochMillis = 40,
                parsedEntries = 1,
            ),
        )
        assertThat(database.sourceRevisionDao().updateActiveRevision(SOURCE_A, 2)).isEqualTo(1)

        val publication = dao.replaceIfCurrent(
            snapshot = snapshot,
            matches = listOf(
                EpgChannelMatchEntity(
                    epgSourceId = EPG_SOURCE,
                    epgRevisionNumber = 1,
                    providerSourceId = SOURCE_A,
                    catalogRevisionNumber = 1,
                    epgExternalChannelId = "news.id",
                    decision = EpgChannelMatchDecision.MATCHED.name,
                    reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                    canonicalChannelId = "canonical-1",
                    candidateCount = 1,
                ),
            ),
        )

        assertThat(publication).isEqualTo(EpgMatchPublicationResult.Superseded)
        assertThat(dao.matchesForEpgSource(EPG_SOURCE)).isEmpty()
    }

    private suspend fun insertCatalogSource(sourceId: String, activeRevision: Long) {
        database.sourceRevisionDao().insertSource(
            SourceEntity(
                id = sourceId,
                name = "Source",
                activeRevision = activeRevision,
            ),
        )
        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = sourceId,
                revisionNumber = activeRevision,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 10,
                activatedAtEpochMillis = 20,
                parsedEntries = 1,
            ),
        )
    }

    private suspend fun insertEpgSource(
        sourceId: String,
        providerSourceId: String,
        activeRevision: Long,
    ) {
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = sourceId,
                name = "Guide",
                providerSourceId = providerSourceId,
                accessRef = null,
                defaultZoneId = "UTC",
                activeRevision = activeRevision,
            ),
        )
        database.epgRevisionDao().insertRevision(
            EpgRevisionEntity(
                sourceId = sourceId,
                revisionNumber = activeRevision,
                status = EpgRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 10,
                activatedAtEpochMillis = 20,
                acceptedChannels = 1,
            ),
        )
    }

    private suspend fun insertProviderChannel(
        id: String,
        sourceId: String,
        revisionNumber: Long,
        canonicalId: String,
        rawName: String,
        tvgId: String?,
        tvgName: String?,
    ) {
        database.sourceRevisionDao().insertCanonicalChannels(
            listOf(CanonicalChannelEntity(id = canonicalId, displayName = rawName)),
        )
        database.sourceRevisionDao().insertProviderChannels(
            listOf(
                ProviderChannelEntity(
                    id = id,
                    sourceId = sourceId,
                    revisionNumber = revisionNumber,
                    providerKey = id,
                    rawName = rawName,
                    tvgId = tvgId,
                    tvgName = tvgName,
                ),
            ),
        )
        database.sourceRevisionDao().insertStreamVariants(
            listOf(
                StreamVariantEntity(
                    id = "variant-$id",
                    providerChannelId = id,
                    canonicalChannelId = canonicalId,
                    locator = "https://example.invalid/$id.m3u8",
                ),
            ),
        )
    }

    private suspend fun insertEpgChannel(externalId: String, displayName: String?) {
        database.epgRevisionDao().insertChannels(
            listOf(
                EpgChannelEntity(
                    sourceId = EPG_SOURCE,
                    revisionNumber = 1,
                    externalId = externalId,
                    primaryDisplayName = displayName,
                    primaryLanguage = null,
                    iconRef = null,
                ),
            ),
        )
    }

    private companion object {
        const val SOURCE_A = "source-a"
        const val SOURCE_B = "source-b"
        const val EPG_SOURCE = "epg-a"
    }
}

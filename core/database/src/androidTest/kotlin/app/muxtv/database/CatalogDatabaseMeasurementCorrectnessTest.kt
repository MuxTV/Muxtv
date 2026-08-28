package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelSearchQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDatabaseMeasurementCorrectnessTest {
    @Test
    fun selectiveAndBroadSearchPublishTheirOwnProgrammeBoundaryAt10kScale() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        try {
            DatabaseInitializer(database).initialize()
            val sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
            sourceStore.upsertSource(SourceDefinition(SOURCE_ID, "M0 correctness source"))
            sourceStore.beginRevision(
                sourceId = SOURCE_ID,
                revisionNumber = CATALOG_REVISION,
                startedAtEpochMillis = STARTED_AT_EPOCH_MILLIS,
            )

            (0 until CORRECTNESS_ENTRY_COUNT).chunked(BATCH_SIZE).forEach { indices ->
                sourceStore.stageBatch(
                    sourceId = SOURCE_ID,
                    revisionNumber = CATALOG_REVISION,
                    entries = indices.map(::catalogEntry),
                )
            }
            val activation = sourceStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = CATALOG_REVISION,
                activatedAtEpochMillis = ACTIVATED_AT_EPOCH_MILLIS,
                statistics = SourceRevisionStatistics(
                    parsedEntries = CORRECTNESS_ENTRY_COUNT,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            )
            assertThat(activation).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)

            prepareBoundaryGuide(database)

            val repository = RoomChannelSearchRepository(
                dataSource = database.channelSearchDao(),
                guideRepository = RoomEpgGuideRepository(database.epgGuideDao()),
            )
            val finalIndex = CORRECTNESS_ENTRY_COUNT - 1
            val finalSuffix = finalIndex.toString().padStart(5, '0')

            val selective = repository.observe(
                ChannelSearchQuery(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    text = CORRECTNESS_ENTRY_COUNT.toString(),
                    nowEpochMillis = SEARCH_NOW_EPOCH_MILLIS,
                    limit = SEARCH_RESULT_LIMIT,
                ),
            ).first()
            assertThat(selective.results.map { it.channel.channelId })
                .containsExactly("canonical-$finalSuffix")
            assertThat(selective.results.single().currentProgrammeTitle)
                .isEqualTo("Programme CrossSignal$finalSuffix")
            assertThat(selective.nextBoundaryEpochMillis)
                .isEqualTo(FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS + finalIndex)

            val broad = repository.observe(
                ChannelSearchQuery(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    text = "Synthetic",
                    nowEpochMillis = SEARCH_NOW_EPOCH_MILLIS,
                    limit = SEARCH_RESULT_LIMIT,
                ),
            ).first()
            assertThat(broad.results.map { it.channel.channelId }).containsExactlyElementsIn(
                List(SEARCH_RESULT_LIMIT) { index ->
                    "canonical-${index.toString().padStart(5, '0')}"
                },
            ).inOrder()
            assertThat(broad.isTruncated).isTrue()
            assertThat(broad.nextBoundaryEpochMillis)
                .isEqualTo(FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS)
        } finally {
            database.close()
        }
    }

    private suspend fun prepareBoundaryGuide(database: MuxTvDatabase) {
        val epgStore = RoomEpgRevisionStore(database.epgRevisionDao())
        epgStore.upsertSource(
            EpgSourceDefinition(
                id = EPG_SOURCE_ID,
                name = "M0 correctness guide",
                providerSourceId = SOURCE_ID,
                accessRef = null,
                defaultZoneId = "UTC",
            ),
        )
        val epgRevision = epgStore.beginRevision(
            sourceId = EPG_SOURCE_ID,
            startedAtEpochMillis = STARTED_AT_EPOCH_MILLIS,
        )
        val boundaryIndices = listOf(0, CORRECTNESS_ENTRY_COUNT - 1)
        epgStore.stageBatch(
            channels = boundaryIndices.map { index -> epgChannel(epgRevision, index) },
            programmes = boundaryIndices.flatMap { index -> epgProgrammes(epgRevision, index) },
        )
        val activation = epgStore.activateRevision(
            sourceId = EPG_SOURCE_ID,
            revisionNumber = epgRevision,
            activatedAtEpochMillis = ACTIVATED_AT_EPOCH_MILLIS,
            statistics = EpgRevisionStatistics(
                acceptedChannels = boundaryIndices.size,
                acceptedProgrammes = boundaryIndices.size * 2,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )
        assertThat(activation).isInstanceOf(EpgRevisionActivationResult.Activated::class.java)

        val relation = requireNotNull(database.epgMatchingDao().relationSnapshot(EPG_SOURCE_ID))
        val publication = database.epgMatchingDao().replaceIfCurrent(
            snapshot = relation,
            matches = boundaryIndices.map { index ->
                val suffix = index.toString().padStart(5, '0')
                EpgChannelMatchEntity(
                    epgSourceId = EPG_SOURCE_ID,
                    epgRevisionNumber = epgRevision,
                    providerSourceId = SOURCE_ID,
                    catalogRevisionNumber = CATALOG_REVISION,
                    epgExternalChannelId = "epg-$suffix",
                    matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
                    decision = EpgChannelMatchDecision.MATCHED.name,
                    reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                    canonicalChannelId = "canonical-$suffix",
                    candidateCount = 1,
                )
            },
        )
        assertThat(publication).isEqualTo(EpgMatchPublicationResult.Applied)
    }

    private fun catalogEntry(index: Int): StagedCatalogEntry {
        val suffix = index.toString().padStart(5, '0')
        return StagedCatalogEntry(
            providerChannelId = "provider-$suffix",
            providerKey = "provider-key-$suffix",
            rawName = "Synthetic Channel $suffix",
            canonicalChannelId = "canonical-$suffix",
            canonicalDisplayName = "Synthetic Channel $suffix",
            streamVariantId = "variant-$suffix",
            locator = "https://stream.example/live/$suffix.m3u8",
            tvgId = "measurement-$suffix",
            tvgName = "Synthetic Channel $suffix",
            groupTitle = "M0",
            channelNumber = (index + 1).toString(),
        )
    }

    private fun epgChannel(revision: Long, index: Int): EpgChannelEntity {
        val suffix = index.toString().padStart(5, '0')
        return EpgChannelEntity(
            sourceId = EPG_SOURCE_ID,
            revisionNumber = revision,
            externalId = "epg-$suffix",
            primaryDisplayName = "Synthetic Channel $suffix",
            primaryLanguage = "en",
            iconRef = null,
        )
    }

    private fun epgProgrammes(revision: Long, index: Int): List<EpgProgrammeEntity> {
        val suffix = index.toString().padStart(5, '0')
        val boundary = FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS + index
        return listOf(
            programme(
                revision = revision,
                sequence = index * 2L + 1,
                externalChannelId = "epg-$suffix",
                start = SEARCH_NOW_EPOCH_MILLIS - 60_000L,
                stop = boundary,
                title = "Programme CrossSignal$suffix",
            ),
            programme(
                revision = revision,
                sequence = index * 2L + 2,
                externalChannelId = "epg-$suffix",
                start = boundary,
                stop = boundary + 60_000L,
                title = "Upcoming $suffix",
            ),
        )
    }

    private fun programme(
        revision: Long,
        sequence: Long,
        externalChannelId: String,
        start: Long,
        stop: Long,
        title: String,
    ) = EpgProgrammeEntity(
        sourceId = EPG_SOURCE_ID,
        revisionNumber = revision,
        sequenceNumber = sequence,
        externalChannelId = externalChannelId,
        startEpochMillis = start,
        stopEpochMillis = stop,
        primaryTitle = title,
        primaryLanguage = "en",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )

    private companion object {
        const val CORRECTNESS_ENTRY_COUNT = 10_000
        const val BATCH_SIZE = 250
        const val SEARCH_RESULT_LIMIT = 100
        const val SEARCH_NOW_EPOCH_MILLIS = 1_700_000_000_000L
        const val FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS = SEARCH_NOW_EPOCH_MILLIS + 60_000L
        const val SOURCE_ID = "m0-correctness-source"
        const val EPG_SOURCE_ID = "m0-correctness-epg"
        const val CATALOG_REVISION = 1L
        const val STARTED_AT_EPOCH_MILLIS = 1_000L
        const val ACTIVATED_AT_EPOCH_MILLIS = 2_000L
    }
}

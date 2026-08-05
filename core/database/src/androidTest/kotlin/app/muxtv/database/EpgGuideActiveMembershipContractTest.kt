package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgGuideActiveMembershipContractTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore
    private lateinit var guide: RoomEpgGuideRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        guide = RoomEpgGuideRepository(database.epgGuideDao())

        sourceStore.upsertSource(SourceDefinition(id = SOURCE_ID, name = "Guide membership"))
        stageRevision(
            revisionNumber = 1,
            channelId = ACTIVE_CHANNEL,
            variantCount = 2,
        )
        activateRevision(revisionNumber = 1, entryCount = 2)
        stageRevision(
            revisionNumber = 2,
            channelId = STAGED_CHANNEL,
            variantCount = 1,
        )

        insertActiveEpg()
        publishCurrentRelationWithPoisonedStagedMatch()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun currentMatchCannotResurrectCanonicalChannelWithoutActiveVariant() = runTest {
        val projections = guide.getNowNext(
            NowNextQuery(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelIds = listOf(ACTIVE_CHANNEL, STAGED_CHANNEL),
                nowEpochMillis = NOW,
            ),
        ).associateBy { projection -> projection.canonicalChannelId }

        assertThat(projections.getValue(ACTIVE_CHANNEL).state)
            .isEqualTo(GuideProjectionState.READY)
        assertThat(projections.getValue(STAGED_CHANNEL).state)
            .isEqualTo(GuideProjectionState.NO_GUIDE)
    }

    private suspend fun stageRevision(
        revisionNumber: Long,
        channelId: String,
        variantCount: Int,
    ) {
        require(variantCount > 0)
        sourceStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = revisionNumber * 1_000L,
        )
        sourceStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            entries = List(variantCount) { index ->
                StagedCatalogEntry(
                    providerChannelId = "provider-$revisionNumber-$channelId-$index",
                    providerKey = "tvg:${externalId(channelId)}:$index",
                    rawName = "Guide $channelId $index",
                    canonicalChannelId = channelId,
                    canonicalDisplayName = "Guide $channelId",
                    streamVariantId = "variant-$revisionNumber-$channelId-$index",
                    locator = "https://example.invalid/$revisionNumber/$channelId/$index.m3u8",
                    tvgId = externalId(channelId),
                )
            },
        )
    }

    private suspend fun activateRevision(
        revisionNumber: Long,
        entryCount: Int,
    ) {
        assertThat(
            sourceStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = revisionNumber,
                activatedAtEpochMillis = revisionNumber * 1_000L + 500L,
                statistics = SourceRevisionStatistics(
                    parsedEntries = entryCount,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private suspend fun insertActiveEpg() {
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = EPG_SOURCE_ID,
                name = "Guide membership EPG",
                providerSourceId = SOURCE_ID,
                accessRef = null,
                defaultZoneId = "UTC",
                activeRevision = EPG_REVISION,
            ),
        )
        database.epgRevisionDao().insertRevision(
            EpgRevisionEntity(
                sourceId = EPG_SOURCE_ID,
                revisionNumber = EPG_REVISION,
                status = EpgRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 100L,
                activatedAtEpochMillis = 200L,
                acceptedChannels = 2,
                acceptedProgrammes = 2,
            ),
        )
        database.epgRevisionDao().insertChannels(
            listOf(ACTIVE_CHANNEL, STAGED_CHANNEL).map { channelId ->
                EpgChannelEntity(
                    sourceId = EPG_SOURCE_ID,
                    revisionNumber = EPG_REVISION,
                    externalId = externalId(channelId),
                    primaryDisplayName = "EPG $channelId",
                    primaryLanguage = "en",
                    iconRef = null,
                )
            },
        )
        database.epgRevisionDao().insertProgrammes(
            listOf(ACTIVE_CHANNEL, STAGED_CHANNEL).mapIndexed { index, channelId ->
                EpgProgrammeEntity(
                    sourceId = EPG_SOURCE_ID,
                    revisionNumber = EPG_REVISION,
                    sequenceNumber = (index + 1).toLong(),
                    externalChannelId = externalId(channelId),
                    startEpochMillis = NOW - 500L,
                    stopEpochMillis = NOW + 500L,
                    primaryTitle = "Programme $channelId",
                    primaryLanguage = "en",
                    subtitle = null,
                    description = null,
                    category = null,
                    iconRef = null,
                    episodeNumber = null,
                    isNew = false,
                )
            },
        )
    }

    private suspend fun publishCurrentRelationWithPoisonedStagedMatch() {
        val snapshot = requireNotNull(
            database.epgMatchingDao().relationSnapshot(EPG_SOURCE_ID),
        )
        assertThat(snapshot.catalogRevisionNumber).isEqualTo(1L)
        assertThat(
            database.epgMatchingDao().replaceIfCurrent(
                snapshot = snapshot,
                matches = listOf(ACTIVE_CHANNEL, STAGED_CHANNEL).map { channelId ->
                    EpgChannelMatchEntity(
                        epgSourceId = EPG_SOURCE_ID,
                        epgRevisionNumber = EPG_REVISION,
                        providerSourceId = SOURCE_ID,
                        catalogRevisionNumber = snapshot.catalogRevisionNumber,
                        epgExternalChannelId = externalId(channelId),
                        decision = EpgChannelMatchDecision.MATCHED.name,
                        reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                        canonicalChannelId = channelId,
                        candidateCount = 1,
                    )
                },
            ),
        ).isEqualTo(EpgMatchPublicationResult.Applied)
    }

    private fun externalId(channelId: String): String = "epg-$channelId"

    private companion object {
        const val SOURCE_ID = "source-guide-membership"
        const val EPG_SOURCE_ID = "epg-guide-membership"
        const val EPG_REVISION = 1L
        const val ACTIVE_CHANNEL = "channel-active"
        const val STAGED_CHANNEL = "channel-staged"
        const val NOW = 1_500L
    }
}

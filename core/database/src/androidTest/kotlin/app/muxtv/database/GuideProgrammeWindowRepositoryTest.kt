package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideProjectionState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuideProgrammeWindowRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var repository: RoomGuideWindowRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        val sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        val epgGuideRepository = RoomEpgGuideRepository(database.epgGuideDao())
        repository = RoomGuideWindowRepository(
            dao = database.guideWindowDao(),
            invalidationSource = epgGuideRepository,
        )

        sourceStore.upsertSource(SourceDefinition(SOURCE_ID, "Programme source"))
        sourceStore.beginRevision(SOURCE_ID, 1, 100)
        sourceStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = CHANNELS.mapIndexed { index, channelId ->
                StagedCatalogEntry(
                    providerChannelId = "provider-$channelId",
                    providerKey = "provider:$channelId",
                    rawName = "Channel $channelId",
                    canonicalChannelId = channelId,
                    canonicalDisplayName = "Channel $channelId",
                    streamVariantId = "variant-$channelId",
                    locator = "https://example.invalid/$channelId.m3u8",
                    channelNumber = (index + 1).toString(),
                )
            },
        )
        assertThat(
            sourceStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                activatedAtEpochMillis = 200,
                statistics = SourceRevisionStatistics(
                    parsedEntries = CHANNELS.size,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_B,
                isHidden = true,
            ),
        )

        insertEpgSource(EPG_SOURCE_ONE)
        insertEpgSource(EPG_SOURCE_TWO)
        insertEpgChannels(
            EPG_SOURCE_ONE,
            listOf(EXTERNAL_A, EXTERNAL_B, EXTERNAL_C_ONE, EXTERNAL_D, EXTERNAL_E),
        )
        insertEpgChannels(EPG_SOURCE_TWO, listOf(EXTERNAL_C_TWO))

        database.epgRevisionDao().insertProgrammes(
            listOf(
                programme(EPG_SOURCE_ONE, 1, EXTERNAL_A, 1_000, 2_000, "A current"),
                programme(EPG_SOURCE_ONE, 2, EXTERNAL_A, 2_000, 3_000, "A middle"),
                programme(EPG_SOURCE_ONE, 3, EXTERNAL_A, 3_000, 4_000, "A trailing"),
                programme(EPG_SOURCE_ONE, 4, EXTERNAL_A, 4_000, 5_000, "A outside"),
                programme(EPG_SOURCE_ONE, 5, EXTERNAL_B, 1_000, 2_000, "B hidden"),
                programme(EPG_SOURCE_ONE, 6, EXTERNAL_C_ONE, 1_000, 2_000, "C source one"),
                programme(EPG_SOURCE_ONE, 7, EXTERNAL_D, 1_000, null, "D open"),
                programme(EPG_SOURCE_ONE, 8, EXTERNAL_D, 2_500, 3_500, "D next"),
                programme(EPG_SOURCE_ONE, 9, EXTERNAL_E, 1_000, null, "E terminal open"),
                programme(EPG_SOURCE_TWO, 1, EXTERNAL_C_TWO, 1_000, 2_000, "C source two"),
            ),
        )

        publishMatches(
            epgSourceId = EPG_SOURCE_ONE,
            matches = listOf(
                matched(EPG_SOURCE_ONE, EXTERNAL_A, CHANNEL_A),
                matched(EPG_SOURCE_ONE, EXTERNAL_B, CHANNEL_B),
                matched(EPG_SOURCE_ONE, EXTERNAL_C_ONE, CHANNEL_C),
                matched(EPG_SOURCE_ONE, EXTERNAL_D, CHANNEL_D),
                matched(EPG_SOURCE_ONE, EXTERNAL_E, CHANNEL_E),
            ),
        )
        publishMatches(
            epgSourceId = EPG_SOURCE_TWO,
            matches = listOf(
                matched(EPG_SOURCE_TWO, EXTERNAL_C_TWO, CHANNEL_C),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun programmeWindowAppliesOverlapMembershipStatesAndEffectiveEnds() = runTest {
        val window = repository.getProgrammeWindow(
            GuideProgrammeWindowQuery(
                profileId = PROFILE_ID,
                canonicalChannelIds = CHANNELS,
                fromEpochMillis = 1_500,
                toEpochMillis = 3_500,
                limit = 100,
            ),
        )

        assertThat(window.channels.map { channel -> channel.canonicalChannelId })
            .containsExactlyElementsIn(CHANNELS)
            .inOrder()
        assertThat(window.isTruncated).isFalse()

        val channelA = window.channels.single { it.canonicalChannelId == CHANNEL_A }
        assertThat(channelA.state).isEqualTo(GuideProjectionState.READY)
        assertThat(channelA.programmes.map { it.title })
            .containsExactly("A current", "A middle", "A trailing")
            .inOrder()
        assertThat(channelA.programmes.map { it.key.sequenceNumber })
            .containsExactly(1L, 2L, 3L)
            .inOrder()

        val channelB = window.channels.single { it.canonicalChannelId == CHANNEL_B }
        assertThat(channelB.state).isEqualTo(GuideProjectionState.NO_GUIDE)
        assertThat(channelB.programmes).isEmpty()

        val channelC = window.channels.single { it.canonicalChannelId == CHANNEL_C }
        assertThat(channelC.state).isEqualTo(GuideProjectionState.SOURCE_CONFLICT)
        assertThat(channelC.programmes).isEmpty()

        val channelD = window.channels.single { it.canonicalChannelId == CHANNEL_D }
        assertThat(channelD.state).isEqualTo(GuideProjectionState.READY)
        assertThat(channelD.programmes.map { it.title })
            .containsExactly("D open", "D next")
            .inOrder()
        assertThat(channelD.programmes.first().startEpochMillis).isEqualTo(1_000)
        assertThat(channelD.programmes.first().endEpochMillis).isEqualTo(2_500)

        val channelE = window.channels.single { it.canonicalChannelId == CHANNEL_E }
        assertThat(channelE.state).isEqualTo(GuideProjectionState.READY)
        assertThat(channelE.programmes).isEmpty()

        val channelF = window.channels.single { it.canonicalChannelId == CHANNEL_F }
        assertThat(channelF.state).isEqualTo(GuideProjectionState.NO_GUIDE)
        assertThat(channelF.programmes).isEmpty()
    }

    @Test
    fun programmeWindowUsesExtraRowForExplicitTruncation() = runTest {
        val window = repository.getProgrammeWindow(
            GuideProgrammeWindowQuery(
                profileId = PROFILE_ID,
                canonicalChannelIds = listOf(CHANNEL_A),
                fromEpochMillis = 1_500,
                toEpochMillis = 3_500,
                limit = 2,
            ),
        )

        assertThat(window.isTruncated).isTrue()
        assertThat(window.channels).hasSize(1)
        assertThat(window.channels.single().programmes.map { it.title })
            .containsExactly("A current", "A middle")
            .inOrder()
    }

    private suspend fun insertEpgSource(sourceId: String) {
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = sourceId,
                name = "Guide $sourceId",
                providerSourceId = SOURCE_ID,
                accessRef = null,
                defaultZoneId = "UTC",
                activeRevision = EPG_REVISION,
            ),
        )
        database.epgRevisionDao().insertRevision(
            EpgRevisionEntity(
                sourceId = sourceId,
                revisionNumber = EPG_REVISION,
                status = EpgRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 100,
                activatedAtEpochMillis = 200,
                acceptedChannels = 10,
                acceptedProgrammes = 10,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )
    }

    private suspend fun insertEpgChannels(sourceId: String, externalIds: List<String>) {
        database.epgRevisionDao().insertChannels(
            externalIds.map { externalId ->
                EpgChannelEntity(
                    sourceId = sourceId,
                    revisionNumber = EPG_REVISION,
                    externalId = externalId,
                    primaryDisplayName = externalId,
                    primaryLanguage = "en",
                    iconRef = null,
                )
            },
        )
    }

    private suspend fun publishMatches(
        epgSourceId: String,
        matches: List<EpgChannelMatchEntity>,
    ) {
        val snapshot = requireNotNull(database.epgMatchingDao().relationSnapshot(epgSourceId))
        assertThat(
            database.epgMatchingDao().replaceIfCurrent(snapshot, matches),
        ).isEqualTo(EpgMatchPublicationResult.Applied)
    }

    private fun matched(
        epgSourceId: String,
        externalId: String,
        canonicalChannelId: String,
    ): EpgChannelMatchEntity = EpgChannelMatchEntity(
        epgSourceId = epgSourceId,
        epgRevisionNumber = EPG_REVISION,
        providerSourceId = SOURCE_ID,
        catalogRevisionNumber = CATALOG_REVISION,
        epgExternalChannelId = externalId,
        decision = EpgChannelMatchDecision.MATCHED.name,
        reasonCode = EpgMatchReasonCode.EXACT_ID.name,
        canonicalChannelId = canonicalChannelId,
        candidateCount = 1,
    )

    private fun programme(
        sourceId: String,
        sequence: Long,
        externalChannelId: String,
        start: Long,
        stop: Long?,
        title: String,
    ): EpgProgrammeEntity = EpgProgrammeEntity(
        sourceId = sourceId,
        revisionNumber = EPG_REVISION,
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
        const val PROFILE_ID = DatabaseDefaults.PRIMARY_PROFILE_ID
        const val SOURCE_ID = "programme-source"
        const val CATALOG_REVISION = 1L
        const val EPG_REVISION = 1L
        const val EPG_SOURCE_ONE = "epg-one"
        const val EPG_SOURCE_TWO = "epg-two"
        const val CHANNEL_A = "channel-a"
        const val CHANNEL_B = "channel-b"
        const val CHANNEL_C = "channel-c"
        const val CHANNEL_D = "channel-d"
        const val CHANNEL_E = "channel-e"
        const val CHANNEL_F = "channel-f"
        const val EXTERNAL_A = "external-a"
        const val EXTERNAL_B = "external-b"
        const val EXTERNAL_C_ONE = "external-c-one"
        const val EXTERNAL_C_TWO = "external-c-two"
        const val EXTERNAL_D = "external-d"
        const val EXTERNAL_E = "external-e"

        val CHANNELS = listOf(
            CHANNEL_A,
            CHANNEL_B,
            CHANNEL_C,
            CHANNEL_D,
            CHANNEL_E,
            CHANNEL_F,
        )
    }
}

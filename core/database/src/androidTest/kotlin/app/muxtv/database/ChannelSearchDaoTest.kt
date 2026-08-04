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
class ChannelSearchDaoTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore
    private lateinit var epgStore: EpgRevisionStore
    private lateinit var dao: ChannelSearchDao

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        epgStore = RoomEpgRevisionStore(database.epgRevisionDao())
        dao = database.channelSearchDao()
        sourceStore.upsertSource(SourceDefinition(SOURCE, "Source", credentialRef = null))
        stageCatalogRevision(
            revision = 1,
            rawName = "Россия Первый",
            displayName = "Россия 1",
            group = "Новости",
            number = "001",
        )
        sourceStore.activate(SOURCE, 1, 20, sourceStatistics())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun unicodePrefixFindsActiveMetadataAcrossFields() = runTest {
        assertThat(candidateIds(SearchQueryEncoder.encode("Россия").single().ftsExpression))
            .containsExactly(CHANNEL)
        assertThat(candidateIds("нов*")).containsExactly(CHANNEL)
        assertThat(candidateIds("001*")).containsExactly(CHANNEL)

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelId = CHANNEL,
                customName = "Мой русский",
                channelNumber = 77,
            ),
        )

        assertThat(candidateIds("мой*")).containsExactly(CHANNEL)
        assertThat(candidateIds("77*")).containsExactly(CHANNEL)
    }

    @Test
    fun overlayDocumentsAreIsolatedToRequestedProfile() = runTest {
        val secondaryProfile = "profile-secondary"
        database.profileDao().insert(
            ProfileEntity(id = secondaryProfile, name = "Secondary", isPrimary = false),
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelId = CHANNEL,
                customName = "Профильное имя",
                channelNumber = 77,
            ),
        )

        assertThat(candidateIds("проф*", profileId = DatabaseDefaults.PRIMARY_PROFILE_ID))
            .containsExactly(CHANNEL)
        assertThat(candidateIds("проф*", profileId = secondaryProfile)).isEmpty()
        assertThat(candidateIds("77*", profileId = secondaryProfile)).isEmpty()
    }

    @Test
    fun hiddenChannelIsExcludedForRequestedProfile() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelId = CHANNEL,
                customName = "Россия скрытая",
                isHidden = true,
            ),
        )

        assertThat(candidateIds("рос*")).isEmpty()
    }

    @Test
    fun providerDocumentFromStagingRevisionCannotPublishCandidate() = runTest {
        stageCatalogRevision(
            revision = 2,
            rawName = "Только staging",
            displayName = "Только staging",
            group = "Черновик",
            number = "999",
        )

        assertThat(candidateIds("только*")).isEmpty()
        assertThat(candidateIds("черновик*")).isEmpty()
        assertThat(candidateIds("999*")).isEmpty()
    }

    @Test
    fun currentProgrammeTitleCanProduceCandidate() = runTest {
        val epgRevision = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(programme(1, 1_000, 2_000, "ВЕСТИ")),
        )
        publishMatch(EPG_A, epgRevision, CURRENT_EPG_MATCH_POLICY_VERSION)

        assertThat(candidateIds("вес*", now = 1_500)).containsExactly(CHANNEL)
    }

    @Test
    fun futureAndExpiredProgrammesCannotProduceCandidates() = runTest {
        val epgRevision = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(
                programme(1, 1_000, 1_400, "Прошлое"),
                programme(2, 2_000, 3_000, "Будущее"),
            ),
        )
        publishMatch(EPG_A, epgRevision, CURRENT_EPG_MATCH_POLICY_VERSION)

        assertThat(candidateIds("прош*", now = 1_500)).isEmpty()
        assertThat(candidateIds("буду*", now = 1_500)).isEmpty()
    }

    @Test
    fun openEndedProgrammeUsesNextStartButIsNotInfinite() = runTest {
        val epgRevision = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(
                programme(1, 1_000, null, "Открытая"),
                programme(2, 2_000, 3_000, "Следующая"),
            ),
        )
        publishMatch(EPG_A, epgRevision, CURRENT_EPG_MATCH_POLICY_VERSION)

        assertThat(candidateIds("откр*", now = 1_500)).containsExactly(CHANNEL)
        assertThat(candidateIds("откр*", now = 2_500)).isEmpty()
    }

    @Test
    fun openEndedProgrammeWithoutNextIsNotInfiniteCurrent() = runTest {
        val epgRevision = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(programme(1, 1_000, null, "Бесконечная")),
        )
        publishMatch(EPG_A, epgRevision, CURRENT_EPG_MATCH_POLICY_VERSION)

        assertThat(candidateIds("беск*", now = 1_500)).isEmpty()
    }

    @Test
    fun staleMatchingPolicyCannotPublishProgrammeCandidate() = runTest {
        val epgRevision = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(programme(1, 1_000, 2_000, "Старая политика")),
        )
        publishMatch(EPG_A, epgRevision, LEGACY_UNVERSIONED_MATCH_POLICY_VERSION)

        assertThat(candidateIds("стар*", now = 1_500)).isEmpty()
    }

    @Test
    fun staleCatalogRelationCannotPublishProgrammeCandidate() = runTest {
        val epgRevision = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(programme(1, 1_000, 2_000, "Старая связь")),
        )
        publishMatch(EPG_A, epgRevision, CURRENT_EPG_MATCH_POLICY_VERSION)

        stageCatalogRevision(
            revision = 2,
            rawName = "Россия Новый",
            displayName = "Россия 1",
            group = "Новости",
            number = "001",
        )
        sourceStore.activate(SOURCE, 2, 30, sourceStatistics())

        assertThat(candidateIds("связ*", now = 1_500)).isEmpty()
    }

    @Test
    fun multipleCurrentMappingsAreConflictAndCannotPublishProgrammeCandidate() = runTest {
        val revisionA = stageAndActivateEpg(
            sourceId = EPG_A,
            programmes = listOf(programme(1, 1_000, 2_000, "Конфликт")),
        )
        publishMatch(EPG_A, revisionA, CURRENT_EPG_MATCH_POLICY_VERSION)
        val revisionB = stageAndActivateEpg(
            sourceId = EPG_B,
            programmes = listOf(programme(1, 1_000, 2_000, "Конфликт")),
        )
        publishMatch(EPG_B, revisionB, CURRENT_EPG_MATCH_POLICY_VERSION)

        assertThat(candidateIds("конф*", now = 1_500)).isEmpty()
    }

    private suspend fun candidateIds(
        expression: String,
        now: Long = 1_500,
        profileId: String = DatabaseDefaults.PRIMARY_PROFILE_ID,
    ): List<String> = dao.searchCandidates(
        profileId = profileId,
        ftsExpression = expression,
        nowEpochMillis = now,
        fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
    ).map(ChannelSearchCandidateRow::canonicalChannelId)

    private suspend fun stageCatalogRevision(
        revision: Long,
        rawName: String,
        displayName: String,
        group: String,
        number: String,
    ) {
        sourceStore.beginRevision(SOURCE, revision, startedAtEpochMillis = 10)
        sourceStore.stageBatch(
            sourceId = SOURCE,
            revisionNumber = revision,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-$revision",
                    providerKey = "provider-key-$revision",
                    rawName = rawName,
                    canonicalChannelId = CHANNEL,
                    canonicalDisplayName = displayName,
                    streamVariantId = "variant-$revision",
                    locator = "https://example.invalid/$revision",
                    groupTitle = group,
                    channelNumber = number,
                    tvgName = displayName,
                ),
            ),
        )
    }

    private suspend fun stageAndActivateEpg(
        sourceId: String,
        programmes: List<EpgProgrammeEntity>,
    ): Long {
        epgStore.upsertSource(
            EpgSourceDefinition(
                id = sourceId,
                name = "Guide $sourceId",
                providerSourceId = SOURCE,
                accessRef = null,
                defaultZoneId = "UTC",
            ),
        )
        val revision = epgStore.beginRevision(sourceId, startedAtEpochMillis = 10)
        val channel = EpgChannelEntity(
            sourceId = sourceId,
            revisionNumber = revision,
            externalId = EXTERNAL_CHANNEL,
            primaryDisplayName = "Channel",
            primaryLanguage = null,
            iconRef = null,
        )
        epgStore.stageBatch(
            channels = listOf(channel),
            programmes = programmes.map { it.copy(sourceId = sourceId, revisionNumber = revision) },
        )
        epgStore.activateRevision(
            sourceId = sourceId,
            revisionNumber = revision,
            activatedAtEpochMillis = 20,
            statistics = EpgRevisionStatistics(
                acceptedChannels = 1,
                acceptedProgrammes = programmes.size,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )
        return revision
    }

    private suspend fun publishMatch(sourceId: String, revision: Long, policyVersion: Int) {
        val snapshot = requireNotNull(database.epgMatchingDao().relationSnapshot(sourceId))
        assertThat(
            database.epgMatchingDao().replaceIfCurrent(
                snapshot = snapshot,
                matches = listOf(
                    EpgChannelMatchEntity(
                        epgSourceId = sourceId,
                        epgRevisionNumber = revision,
                        providerSourceId = SOURCE,
                        catalogRevisionNumber = 1,
                        epgExternalChannelId = EXTERNAL_CHANNEL,
                        matchPolicyVersion = policyVersion,
                        decision = EpgChannelMatchDecision.MATCHED.name,
                        reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                        canonicalChannelId = CHANNEL,
                        candidateCount = 1,
                    ),
                ),
            ),
        ).isEqualTo(EpgMatchPublicationResult.Applied)
    }

    private fun programme(
        sequence: Long,
        start: Long,
        stop: Long?,
        title: String,
    ) = EpgProgrammeEntity(
        sourceId = "placeholder",
        revisionNumber = 1,
        sequenceNumber = sequence,
        externalChannelId = EXTERNAL_CHANNEL,
        startEpochMillis = start,
        stopEpochMillis = stop,
        primaryTitle = title,
        primaryLanguage = "ru",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )

    private fun sourceStatistics() = SourceRevisionStatistics(
        parsedEntries = 1,
        skippedEntries = 0,
        warningCount = 0,
    )

    private companion object {
        const val SOURCE = "search-source"
        const val CHANNEL = "search-channel"
        const val EPG_A = "search-epg-a"
        const val EPG_B = "search-epg-b"
        const val EXTERNAL_CHANNEL = "epg-channel"
    }
}

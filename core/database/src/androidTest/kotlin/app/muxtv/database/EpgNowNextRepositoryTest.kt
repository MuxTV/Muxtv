package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgNowNextRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var repository: RoomEpgGuideRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        repository = RoomEpgGuideRepository(database.epgGuideDao())
        database.profileDao().insert(
            ProfileEntity(id = PROFILE, name = "Primary", isPrimary = true),
        )
        insertCatalogSource(SOURCE, revision = 1)
        database.catalogDao().insertCanonicalChannel(
            CanonicalChannelEntity(id = CANONICAL, displayName = "Channel"),
        )
        insertEpgSource(EPG_A, revision = 1)
        insertEpgChannel(EPG_A, revision = 1, externalId = CHANNEL_A)
        publishMatch(EPG_A, revision = 1, externalId = CHANNEL_A)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun returnsCurrentNextAndEarliestBoundaryForSingleCurrentGuide() = runTest {
        insertProgramme(EPG_A, 1, 1, CHANNEL_A, 1_000, 2_000, "Current")
        insertProgramme(EPG_A, 1, 2, CHANNEL_A, 2_000, 3_000, "Next")

        val projection = repository.getNowNext(query(now = 1_500)).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.READY)
        assertThat(projection.current).isEqualTo(GuideProgramme(1_000, 2_000, "Current"))
        assertThat(projection.next).isEqualTo(GuideProgramme(2_000, 3_000, "Next"))
        assertThat(projection.nextBoundaryEpochMillis).isEqualTo(2_000)
    }

    @Test
    fun hiddenOverlaySuppressesGuideProjectionForThatProfile() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE,
                canonicalChannelId = CANONICAL,
                isHidden = true,
            ),
        )
        insertProgramme(EPG_A, 1, 1, CHANNEL_A, 1_000, 2_000, "Current")

        val projection = repository.getNowNext(query(now = 1_500)).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.NO_GUIDE)
        assertThat(projection.current).isNull()
        assertThat(projection.next).isNull()
    }

    @Test
    fun staleMatchFromPreviousCatalogRevisionIsInvisible() = runTest {
        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = SOURCE,
                revisionNumber = 2,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 30,
                activatedAtEpochMillis = 40,
                parsedEntries = 1,
            ),
        )
        assertThat(database.sourceRevisionDao().updateActiveRevision(SOURCE, 2)).isEqualTo(1)
        insertProgramme(EPG_A, 1, 1, CHANNEL_A, 1_000, 2_000, "Stale")

        val projection = repository.getNowNext(query(now = 1_500)).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.NO_GUIDE)
    }

    @Test
    fun openEndedCurrentUsesFollowingProgrammeStartAsEffectiveEnd() = runTest {
        insertProgramme(EPG_A, 1, 1, CHANNEL_A, 1_000, null, "Open")
        insertProgramme(EPG_A, 1, 2, CHANNEL_A, 2_000, 3_000, "Next")

        val projection = repository.getNowNext(query(now = 1_500)).single()

        assertThat(projection.current).isEqualTo(GuideProgramme(1_000, 2_000, "Open"))
        assertThat(projection.next).isEqualTo(GuideProgramme(2_000, 3_000, "Next"))
        assertThat(projection.nextBoundaryEpochMillis).isEqualTo(2_000)
    }

    @Test
    fun openEndedProgrammeWithoutFollowingProgrammeIsNotInfiniteCurrent() = runTest {
        insertProgramme(EPG_A, 1, 1, CHANNEL_A, 1_000, null, "Open")

        val projection = repository.getNowNext(query(now = 1_500)).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.READY)
        assertThat(projection.current).isNull()
        assertThat(projection.next).isNull()
        assertThat(projection.nextBoundaryEpochMillis).isNull()
    }

    @Test
    fun multipleCurrentGuideMappingsReturnSourceConflictInsteadOfChoosingOne() = runTest {
        insertEpgSource(EPG_B, revision = 1)
        insertEpgChannel(EPG_B, revision = 1, externalId = CHANNEL_B)
        publishMatch(EPG_B, revision = 1, externalId = CHANNEL_B)
        insertProgramme(EPG_A, 1, 1, CHANNEL_A, 1_000, 2_000, "A")
        insertProgramme(EPG_B, 1, 1, CHANNEL_B, 1_000, 2_000, "B")

        val projection = repository.getNowNext(query(now = 1_500)).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.SOURCE_CONFLICT)
        assertThat(projection.current).isNull()
        assertThat(projection.next).isNull()
    }

    @Test
    fun requestedChannelWithoutCurrentMatchReturnsNoGuide() = runTest {
        database.sourceRevisionDao().insertCanonicalChannels(
            listOf(CanonicalChannelEntity(id = "canonical-unmatched", displayName = "Unmatched")),
        )

        val projection = repository.getNowNext(
            NowNextQuery(
                profileId = PROFILE,
                canonicalChannelIds = listOf("canonical-unmatched"),
                nowEpochMillis = 1_500,
            ),
        ).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.NO_GUIDE)
    }

    @Test
    fun dataChangeSignalEmitsWhenActiveCatalogRevisionChanges() = runTest {
        val firstEmission = CompletableDeferred<Unit>()
        val emissions = async {
            repository.observeDataChanges()
                .onEach { firstEmission.complete(Unit) }
                .take(2)
                .toList()
        }
        runCurrent()
        firstEmission.await()

        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = SOURCE,
                revisionNumber = 2,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 30,
                activatedAtEpochMillis = 40,
                parsedEntries = 0,
            ),
        )
        assertThat(database.sourceRevisionDao().updateActiveRevision(SOURCE, 2)).isEqualTo(1)

        assertThat(emissions.await()).containsExactly(Unit, Unit).inOrder()
    }

    private fun query(now: Long) = NowNextQuery(
        profileId = PROFILE,
        canonicalChannelIds = listOf(CANONICAL),
        nowEpochMillis = now,
    )

    private suspend fun insertCatalogSource(sourceId: String, revision: Long) {
        database.sourceRevisionDao().insertSource(
            SourceEntity(id = sourceId, name = "Playlist", activeRevision = revision),
        )
        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = sourceId,
                revisionNumber = revision,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 10,
                activatedAtEpochMillis = 20,
                parsedEntries = 1,
            ),
        )
    }

    private suspend fun insertEpgSource(sourceId: String, revision: Long) {
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = sourceId,
                name = "Guide",
                providerSourceId = SOURCE,
                accessRef = null,
                defaultZoneId = "UTC",
                activeRevision = revision,
            ),
        )
        database.epgRevisionDao().insertRevision(
            EpgRevisionEntity(
                sourceId = sourceId,
                revisionNumber = revision,
                status = EpgRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 10,
                activatedAtEpochMillis = 20,
                acceptedChannels = 1,
            ),
        )
    }

    private suspend fun insertEpgChannel(sourceId: String, revision: Long, externalId: String) {
        database.epgRevisionDao().insertChannels(
            listOf(
                EpgChannelEntity(
                    sourceId = sourceId,
                    revisionNumber = revision,
                    externalId = externalId,
                    primaryDisplayName = "Channel",
                    primaryLanguage = null,
                    iconRef = null,
                ),
            ),
        )
    }

    private suspend fun publishMatch(sourceId: String, revision: Long, externalId: String) {
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
                        epgExternalChannelId = externalId,
                        decision = EpgChannelMatchDecision.MATCHED.name,
                        reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                        canonicalChannelId = CANONICAL,
                        candidateCount = 1,
                    ),
                ),
            ),
        ).isEqualTo(EpgMatchPublicationResult.Applied)
    }

    private suspend fun insertProgramme(
        sourceId: String,
        revision: Long,
        sequence: Long,
        externalId: String,
        start: Long,
        stop: Long?,
        title: String,
    ) {
        database.epgRevisionDao().insertProgrammes(
            listOf(
                EpgProgrammeEntity(
                    sourceId = sourceId,
                    revisionNumber = revision,
                    sequenceNumber = sequence,
                    externalChannelId = externalId,
                    startEpochMillis = start,
                    stopEpochMillis = stop,
                    primaryTitle = title,
                    primaryLanguage = null,
                    subtitle = null,
                    description = null,
                    category = null,
                    iconRef = null,
                    episodeNumber = null,
                    isNew = false,
                ),
            ),
        )
    }

    private companion object {
        const val PROFILE = "profile-1"
        const val SOURCE = "source-1"
        const val EPG_A = "epg-a"
        const val EPG_B = "epg-b"
        const val CHANNEL_A = "channel-a"
        const val CHANNEL_B = "channel-b"
        const val CANONICAL = "canonical-1"
    }
}

package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.database.measurement.C03ProductionCandidateActivationResult
import app.muxtv.database.measurement.C03ProductionCandidateDatabase
import app.muxtv.database.measurement.C03ProductionCandidateStageEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C03ProductionCandidateCorrectnessTest {
    private lateinit var database: C03ProductionCandidateDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            C03ProductionCandidateDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun identicalPayloadIsReusedAcrossRevisionMemberships() = runTest {
        val dao = database.candidateDao()
        dao.upsertSource(SOURCE_ID, CREDENTIAL_REF)
        dao.setRunningRefreshOwner(SOURCE_ID, RUN_TOKEN_A)

        dao.beginRevision(SOURCE_ID, revisionNumber = 1, startedAtEpochMillis = 1_000)
        dao.stageBatch(
            SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(entry(ordinal = 0)),
        )
        assertThat(
            dao.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_TOKEN_A,
                activatedAtEpochMillis = 2_000,
            ),
        ).isEqualTo(C03ProductionCandidateActivationResult.Published)

        dao.beginRevision(SOURCE_ID, revisionNumber = 2, startedAtEpochMillis = 3_000)
        dao.stageBatch(
            SOURCE_ID,
            revisionNumber = 2,
            entries = listOf(entry(ordinal = 0)),
        )

        val stagedCounts = dao.rowCounts()
        assertThat(stagedCounts.payloadRows).isEqualTo(1)
        assertThat(stagedCounts.searchPayloadRows).isEqualTo(1)
        assertThat(stagedCounts.membershipRows).isEqualTo(2)

        assertThat(
            dao.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = 2,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_TOKEN_A,
                activatedAtEpochMillis = 4_000,
            ),
        ).isEqualTo(C03ProductionCandidateActivationResult.Published)

        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(2)
        assertThat(dao.activeRows(SOURCE_ID).map { it.logicalChannelId })
            .containsExactly(LOGICAL_CHANNEL_ID)
            .inOrder()
        assertThat(dao.retainedRevisions(SOURCE_ID)).containsExactly(1L)
    }

    @Test
    fun staleRefreshOwnerCannotPublishAndStagingIsDiscarded() = runTest {
        val dao = database.candidateDao()
        dao.upsertSource(SOURCE_ID, CREDENTIAL_REF)
        dao.setRunningRefreshOwner(SOURCE_ID, RUN_TOKEN_A)
        dao.beginRevision(SOURCE_ID, revisionNumber = 1, startedAtEpochMillis = 1_000)
        dao.stageBatch(
            SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(entry(ordinal = 0)),
        )

        dao.setRunningRefreshOwner(SOURCE_ID, RUN_TOKEN_B)

        assertThat(
            dao.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_TOKEN_A,
                activatedAtEpochMillis = 2_000,
            ),
        ).isEqualTo(C03ProductionCandidateActivationResult.Superseded)

        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(0)
        assertThat(dao.membershipCount(SOURCE_ID, revisionNumber = 1)).isEqualTo(0)
        assertThat(dao.revisionExists(SOURCE_ID, revisionNumber = 1)).isFalse()
    }

    private fun entry(ordinal: Long): C03ProductionCandidateStageEntry =
        C03ProductionCandidateStageEntry(
            ordinal = ordinal,
            logicalChannelId = LOGICAL_CHANNEL_ID,
            contentHash = CONTENT_HASH,
            searchContentHash = SEARCH_CONTENT_HASH,
            providerKey = "provider:42",
            rawName = "News HD",
            tvgId = "news-hd",
            tvgName = "News HD",
            logoUrl = "https://images.invalid/news.png",
            groupTitle = "News",
            channelNumber = "42",
            locator = "https://stream.invalid/live/42?token=redacted",
            catchupMode = null,
            catchupSource = null,
            catchupDays = null,
            catchupCorrection = null,
            userAgent = null,
            referrer = null,
            searchText = "News HD News 42",
        )

    private companion object {
        const val SOURCE_ID = "source-c03"
        const val CREDENTIAL_REF = "credential-ref-c03"
        const val RUN_TOKEN_A = "run-a"
        const val RUN_TOKEN_B = "run-b"
        const val LOGICAL_CHANNEL_ID = "logical-channel-42"
        const val CONTENT_HASH = "payload-hash-v1"
        const val SEARCH_CONTENT_HASH = "search-hash-v1"
    }
}

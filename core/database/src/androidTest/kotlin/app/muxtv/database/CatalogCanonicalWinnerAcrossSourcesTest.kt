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

/**
 * Gate E: canonical publication winner is selected across ALL active sources.
 *
 * The no-op UPDATE guard must not pin the previous winner when a different source
 * becomes authoritative. It must also preserve canonical Search publication and
 * profile-owned overlays without changing any public read implementation.
 */
@RunWith(AndroidJUnit4::class)
class CatalogCanonicalWinnerAcrossSourcesTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        database.profileDao().insert(
            ProfileEntity(PROFILE_ID, "Winner profile", isPrimary = false),
        )
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
        store.upsertSource(SourceDefinition(ALPHA_SOURCE, "Alpha source"))
        store.upsertSource(SourceDefinition(BETA_SOURCE, "Beta source"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sharedCanonicalWinnerFollowsActiveSourceAndRetainsUserOverlay() = runTest {
        stageAndActivate(
            ALPHA_SOURCE, 1, listOf(
                entry(ALPHA_SOURCE, 1, "shared", SHARED_ID, "Alpha raw", "Alpha Winner"),
            ),
        )
        assertWinner("Alpha Winner")
        assertUnchangedPublication(ALPHA_SOURCE, 1, 0)

        // Publishing a secondary source with a lexicographically later source ID
        // cannot overwrite the existing deterministic canonical display winner.
        stageAndActivate(
            BETA_SOURCE, 1, listOf(
                entry(BETA_SOURCE, 1, "shared", SHARED_ID, "Beta raw", "Beta Secondary"),
            ),
        )
        assertWinner("Alpha Winner")
        assertUnchangedPublication(BETA_SOURCE, 1, 0)

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = SHARED_ID,
                isFavorite = true,
                customName = "My Channel",
                channelNumber = 73,
            ),
        )
        assertOverlayParity()

        // A refresh changing only the secondary provider's metadata is not
        // allowed to alter the winner or leave stale canonical Search text.
        stageAndActivate(
            BETA_SOURCE, 2, listOf(
                entry(BETA_SOURCE, 2, "shared", SHARED_ID, "Beta raw", "Beta Updated"),
            ),
        )
        assertWinner("Alpha Winner")
        assertUnchangedPublication(BETA_SOURCE, 2, 1)
        assertOverlayParity()

        // The winning source changes its preferred tvgName: this MUST update.
        stageAndActivate(
            ALPHA_SOURCE, 2, listOf(
                entry(ALPHA_SOURCE, 2, "shared", SHARED_ID, "Alpha raw", "Alpha Updated"),
            ),
        )
        assertWinner("Alpha Updated")
        assertUnchangedPublication(ALPHA_SOURCE, 2, 1)
        assertOverlayParity()

        // Alpha no longer contains the shared channel. Its previous revision
        // remains retained, but only Beta is eligible for canonical publication.
        stageAndActivate(
            ALPHA_SOURCE, 3, listOf(
                entry(ALPHA_SOURCE, 3, "other", OTHER_ID, "Other Station", null),
            ),
        )
        assertWinner("Beta Updated")
        assertUnchangedPublication(ALPHA_SOURCE, 3, 2)
        assertOverlayParity()

        val authoritative = database.channelSearchDao().searchCandidates(
            profileId = PROFILE_ID,
            ftsExpression = SearchQueryEncoder.encode("Beta").single().ftsExpression,
            nowEpochMillis = 4_000,
            fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
        )
        val shadow = database.catalogShadowSearchDao().searchCandidates(
            profileId = PROFILE_ID,
            ftsExpression = SearchQueryEncoder.encode("Beta").single().ftsExpression,
            nowEpochMillis = 4_000,
            fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
        )
        assertThat(shadow).containsExactlyElementsIn(authoritative).inOrder()
        assertThat(shadow.map { it.canonicalChannelId }).contains(SHARED_ID)
    }

    private suspend fun assertWinner(expected: String) {
        assertThat(database.catalogDao().findActiveCanonicalChannel(SHARED_ID)?.displayName)
            .isEqualTo(expected)
        assertThat(database.searchIndexDao().textsForCanonicalKind(
            SHARED_ID,
            SearchDocumentKind.CANONICAL_NAME,
        )).containsExactly(expected)
    }

    private suspend fun assertUnchangedPublication(
        sourceId: String,
        current: Long,
        previous: Long,
    ) {
        assertThat(database.sourceRevisionDao().publishCanonicalDisplayMetadata(
            sourceId,
            current,
            previous,
        )).isEqualTo(0)
    }

    private suspend fun assertOverlayParity() {
        val ids = listOf(SHARED_ID)
        val legacy = database.channelSearchDao().activeChannelSummaries(PROFILE_ID, ids)
        val shadow = database.catalogShadowDao().searchActiveSummaries(PROFILE_ID, ids)
        assertThat(shadow).containsExactlyElementsIn(legacy).inOrder()
        assertThat(legacy.single().displayName).isEqualTo("My Channel")
        assertThat(legacy.single().channelNumber).isEqualTo("73")
        assertThat(legacy.single().isFavorite).isTrue()
        assertThat(legacy.single().variantCount).isEqualTo(2.takeIf {
            database.sourceRevisionDao().activeRevision(ALPHA_SOURCE) != 3L
        } ?: 1)
    }

    private suspend fun stageAndActivate(
        sourceId: String,
        revision: Long,
        entries: List<StagedCatalogEntry>,
    ) {
        store.beginRevision(sourceId, revision, revision * 1_000)
        store.stageBatch(sourceId, revision, entries)
        assertThat(store.activate(
            sourceId,
            revision,
            revision * 1_000 + 500,
            SourceRevisionStatistics(entries.size, 0, 0),
        )).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun entry(
        sourceId: String,
        revision: Long,
        key: String,
        canonicalId: String,
        rawName: String,
        tvgName: String?,
    ) = StagedCatalogEntry(
        providerChannelId = "provider-$sourceId-$revision-$key",
        providerKey = "tvg:$key",
        rawName = rawName,
        canonicalChannelId = canonicalId,
        canonicalDisplayName = tvgName ?: rawName,
        streamVariantId = "variant-$sourceId-$revision-$key",
        locator = "https://stream.invalid/$sourceId/$revision/$key",
        tvgName = tvgName,
        groupTitle = "Shared group",
    )

    private companion object {
        const val ALPHA_SOURCE = "a-source"
        const val BETA_SOURCE = "b-source"
        const val SHARED_ID = "canonical-shared-across-sources"
        const val OTHER_ID = "canonical-other-across-sources"
        const val PROFILE_ID = "profile-canonical-winner"
    }
}

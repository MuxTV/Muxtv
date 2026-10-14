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
import kotlin.time.Duration.Companion.minutes

/**
 * D2-B: deliberately cross the 800-token candidate bound while requiring the source-diversity
 * reservation to retain a channel from an otherwise lexicographically starved source.
 * This is candidate-policy evidence only, not an independent v11 FTS index or a read cutover.
 */
@RunWith(AndroidJUnit4::class)
class CatalogShadowSearchCandidateScaleTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun overflowPreservesSourceDiversityAndRestrictedParityAcrossShadowMemberships() =
        runTest(timeout = 5.minutes) {
            store.upsertSource(SourceDefinition(ALPHA_SOURCE, "Alpha"))
            store.upsertSource(SourceDefinition(ZETA_SOURCE, "Zeta"))

            // Every row has the same group-only FTS match rank. The alpha source by itself
            // exceeds the 801-row probe bound; unguarded global ordering would starve zeta.
            val alphaEntries = (0 until ALPHA_COUNT).map { index ->
                entry(ALPHA_SOURCE, "alpha", index)
            }
            stageRevision(ALPHA_SOURCE, alphaEntries)

            // Staging documents must not become candidates before publication.
            assertThat(candidates()).isEmpty()
            activate(ALPHA_SOURCE, alphaEntries.size)
            val alphaOnly = candidates()
            assertThat(alphaOnly).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
            assertThat(alphaOnly.map { it.bestMatchRank }.distinct())
                .containsExactly(ChannelSearchMatchRank.GROUP)

            val zetaEntries = (0 until ZETA_COUNT).map { index ->
                entry(ZETA_SOURCE, "zeta", index)
            }
            stageRevision(ZETA_SOURCE, zetaEntries)
            assertThat(candidates()).containsExactlyElementsIn(alphaOnly).inOrder()
            activate(ZETA_SOURCE, zetaEntries.size)

            val published = candidates()
            assertThat(published).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
            assertThat(published.take(2).map { it.canonicalChannelId })
                .containsExactly("channel-alpha-0000", "channel-zeta-0000")
                .inOrder()
            assertThat(published.map { it.bestMatchRank }.distinct())
                .containsExactly(ChannelSearchMatchRank.GROUP)
            assertThat(database.catalogShadowDao().membershipCount())
                .isEqualTo(ALPHA_COUNT + ZETA_COUNT)

            // A bounded re-probe over a small seed must bypass source reservations and
            // preserve requested ID restriction even when a broad unrestricted probe overflows.
            val restricted = listOf("channel-alpha-0804", "channel-zeta-0001")
            val expectedRestricted = authoritative(restrictTo = restricted, fetchLimit = 2)
            val actualRestricted = shadow(restrictTo = restricted, fetchLimit = 2)
            assertThat(actualRestricted).containsExactlyElementsIn(expectedRestricted).inOrder()
            assertThat(actualRestricted.map { it.canonicalChannelId })
                .containsExactly("channel-alpha-0804", "channel-zeta-0001")
                .inOrder()

            // A profile-hidden alpha representative cannot reappear via the shadow
            // source-diversity reservation. The second alpha member takes its place.
            database.catalogDao().insertOverlay(
                UserChannelOverlayEntity(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    canonicalChannelId = "channel-alpha-0000",
                    isHidden = true,
                ),
            )
            val afterHide = candidates()
            assertThat(afterHide).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
            assertThat(afterHide.map { it.canonicalChannelId })
                .doesNotContain("channel-alpha-0000")
            assertThat(afterHide.map { it.canonicalChannelId })
                .contains("channel-zeta-0000")
        }

    private suspend fun stageRevision(
        source: String,
        entries: List<StagedCatalogEntry>,
    ) {
        store.beginRevision(source, 1, 1_000)
        entries.chunked(200).forEach { batch ->
            store.stageBatch(source, 1, batch)
        }
    }

    private suspend fun activate(source: String, size: Int) {
        assertThat(
            store.activate(
                sourceId = source,
                revisionNumber = 1,
                activatedAtEpochMillis = 2_000,
                statistics = SourceRevisionStatistics(size, 0, 0),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private suspend fun candidates(): List<ChannelSearchCandidateRow> {
        val authoritative = authoritative()
        val shadow = shadow()
        assertThat(shadow).containsExactlyElementsIn(authoritative).inOrder()
        return shadow
    }

    private suspend fun authoritative(
        restrictTo: List<String>? = null,
        fetchLimit: Int = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
    ) = database.channelSearchDao().searchCandidates(
        profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
        ftsExpression = "broadcast*",
        nowEpochMillis = 1_500,
        fetchLimit = fetchLimit,
        restrictToCanonicalIds = restrictTo,
    )

    private suspend fun shadow(
        restrictTo: List<String>? = null,
        fetchLimit: Int = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
    ) = database.catalogShadowSearchDao().searchCandidates(
        profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
        ftsExpression = "broadcast*",
        nowEpochMillis = 1_500,
        fetchLimit = fetchLimit,
        restrictToCanonicalIds = restrictTo,
    )

    private fun entry(source: String, prefix: String, index: Int): StagedCatalogEntry {
        val suffix = index.toString().padStart(4, '0')
        val canonical = "channel-$prefix-$suffix"
        return StagedCatalogEntry(
            providerChannelId = "provider-$prefix-$suffix",
            providerKey = "tvg:$prefix-$suffix",
            rawName = "Service $suffix",
            canonicalChannelId = canonical,
            canonicalDisplayName = "Service $suffix",
            streamVariantId = "variant-$prefix-$suffix",
            locator = "https://stream.invalid/$prefix/$suffix",
            groupTitle = "Broadcast",
            channelNumber = suffix,
        )
    }

    private companion object {
        const val ALPHA_SOURCE = "source-search-alpha"
        const val ZETA_SOURCE = "source-search-zeta"
        const val ALPHA_COUNT = 805
        const val ZETA_COUNT = 2
    }
}

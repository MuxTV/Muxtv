package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentChannelsRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var recent: RoomRecentChannelsRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        recent = RoomRecentChannelsRepository(database.recentChannelsDao())
        insertProfile(PROFILE_A, primary = true)
        insertProfile(PROFILE_B, primary = false)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun newerTimestampWinsAndOlderOrDuplicateDeliveryIsIdempotent() = runTest {
        activateRevision(1, listOf("channel-a"))

        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 2_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)
        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 1_000L))
            .isEqualTo(RecentChannelWriteResult.IgnoredOlderOrDuplicate)
        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 2_000L))
            .isEqualTo(RecentChannelWriteResult.IgnoredOlderOrDuplicate)
        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 3_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)

        val row = recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first().single()
        assertThat(row.channel.channelId).isEqualTo("channel-a")
        assertThat(row.lastSuccessfulPlaybackAtEpochMillis).isEqualTo(3_000L)
    }

    @Test
    fun recentHistoryIsIsolatedPerProfile() = runTest {
        activateRevision(1, listOf("channel-a"))

        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 1_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)
        assertThat(recent.recordSuccessfulPlayback(PROFILE_B, "channel-a", 2_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)

        val first = recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first().single()
        val second = recent.observeRecent(RecentChannelsQuery(PROFILE_B)).first().single()
        assertThat(first.lastSuccessfulPlaybackAtEpochMillis).isEqualTo(1_000L)
        assertThat(second.lastSuccessfulPlaybackAtEpochMillis).isEqualTo(2_000L)
    }

    @Test
    fun retentionKeepsOnlyNewestFiftyRowsPerProfile() = runTest {
        val channelIds = (1..55).map { index -> "channel-${index.toString().padStart(2, '0')}" }
        activateRevision(1, channelIds)

        channelIds.forEachIndexed { index, channelId ->
            assertThat(
                recent.recordSuccessfulPlayback(
                    profileId = PROFILE_A,
                    channelId = channelId,
                    successfulAtEpochMillis = (index + 1).toLong(),
                ),
            ).isEqualTo(RecentChannelWriteResult.Applied)
        }

        val rows = recent.observeRecent(
            RecentChannelsQuery(PROFILE_A, limit = RecentChannelsQuery.MAX_LIMIT),
        ).first()
        assertThat(rows).hasSize(50)
        assertThat(rows.first().channel.channelId).isEqualTo("channel-55")
        assertThat(rows.last().channel.channelId).isEqualTo("channel-06")
        assertThat(database.recentChannelsDao().countForProfile(PROFILE_A)).isEqualTo(50)
    }

    @Test
    fun hiddenChannelIsSuppressedWithoutDeletingRecentHistory() = runTest {
        activateRevision(1, listOf("channel-a"))
        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 1_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_A,
                canonicalChannelId = "channel-a",
                isHidden = true,
            ),
        )

        assertThat(recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first()).isEmpty()
        assertThat(database.recentChannelsDao().countForProfile(PROFILE_A)).isEqualTo(1)
    }

    @Test
    fun inactiveChannelHistorySurvivesCatalogCleanupAndReappearsWithSameCanonicalIdentity() =
        runTest {
            activateRevision(1, listOf("channel-a"))
            assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 1_000L))
                .isEqualTo(RecentChannelWriteResult.Applied)

            activateRevision(2, listOf("channel-b"))
            activateRevision(3, listOf("channel-c"))

            assertThat(recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first()).isEmpty()
            assertThat(database.recentChannelsDao().countForProfile(PROFILE_A)).isEqualTo(1)

            activateRevision(4, listOf("channel-a"))

            val restored = recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first().single()
            assertThat(restored.channel.channelId).isEqualTo("channel-a")
            assertThat(restored.lastSuccessfulPlaybackAtEpochMillis).isEqualTo(1_000L)
        }

    @Test
    fun firstFrameIdentityCanBeRecordedAfterCatalogCleanupRace() = runTest {
        activateRevision(1, listOf("channel-a"))
        activateRevision(2, listOf("channel-b"))
        activateRevision(3, listOf("channel-c"))

        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "channel-a", 7_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)
        assertThat(recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first()).isEmpty()
        assertThat(database.recentChannelsDao().countForProfile(PROFILE_A)).isEqualTo(1)

        activateRevision(4, listOf("channel-a"))

        val restored = recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first().single()
        assertThat(restored.lastSuccessfulPlaybackAtEpochMillis).isEqualTo(7_000L)
    }

    @Test
    fun missingProfileIsUnavailableButTrustedLogicalChannelIdentityIsBoundedAndAccepted() = runTest {
        activateRevision(1, listOf("channel-a"))

        assertThat(recent.recordSuccessfulPlayback("missing-profile", "channel-a", 1_000L))
            .isEqualTo(RecentChannelWriteResult.ProfileUnavailable)
        assertThat(recent.recordSuccessfulPlayback(PROFILE_A, "missing-channel", 1_000L))
            .isEqualTo(RecentChannelWriteResult.Applied)
        assertThat(database.recentChannelsDao().countForProfile(PROFILE_A)).isEqualTo(1)
        assertThat(recent.observeRecent(RecentChannelsQuery(PROFILE_A)).first()).isEmpty()
    }

    private suspend fun insertProfile(profileId: String, primary: Boolean) {
        database.profileDao().insert(
            ProfileEntity(
                id = profileId,
                name = profileId,
                isPrimary = primary,
            ),
        )
    }

    private suspend fun activateRevision(
        revisionNumber: Long,
        channelIds: List<String>,
    ) {
        if (revisionNumber == 1L) {
            revisionStore.upsertSource(SourceDefinition(id = SOURCE_ID, name = "Provider"))
        }
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = revisionNumber * 1_000L,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            entries = channelIds.mapIndexed { index, channelId ->
                stagedEntry(
                    revisionNumber = revisionNumber,
                    index = index,
                    channelId = channelId,
                )
            },
        )
        val activation = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = revisionNumber * 1_000L + 500L,
            statistics = SourceRevisionStatistics(
                parsedEntries = channelIds.size,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(activation).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun stagedEntry(
        revisionNumber: Long,
        index: Int,
        channelId: String,
    ) = StagedCatalogEntry(
        providerChannelId = "provider-$revisionNumber-$index",
        providerKey = "tvg:$channelId",
        rawName = "Channel $channelId",
        canonicalChannelId = channelId,
        canonicalDisplayName = "Channel $channelId",
        streamVariantId = "variant-$revisionNumber-$index",
        locator = "https://example.invalid/$channelId.m3u8",
        tvgId = channelId,
        groupTitle = "Group",
        channelNumber = (index + 1).toString(),
    )

    private companion object {
        const val PROFILE_A = "profile-a"
        const val PROFILE_B = "profile-b"
        const val SOURCE_ID = "source-recent"
    }
}

package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.UnhandledPlaybackReferenceResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveChannelTruthContractTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore
    private lateinit var playback: RoomPlaybackCatalog
    private lateinit var guide: RoomEpgGuideRepository
    private lateinit var search: RoomChannelSearchRepository
    private lateinit var recent: RoomRecentChannelsRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        database.profileDao().insert(
            ProfileEntity(
                id = SECONDARY_PROFILE,
                name = "Secondary",
                isPrimary = false,
            ),
        )

        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        playback = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = SecurePlaybackAccessPolicyResolver,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
        )
        guide = RoomEpgGuideRepository(database.epgGuideDao())
        search = RoomChannelSearchRepository(
            dataSource = database.channelSearchDao(),
            guideRepository = guide,
        )
        recent = RoomRecentChannelsRepository(database.recentChannelsDao())

        sourceStore.upsertSource(SourceDefinition(id = SOURCE_ID, name = "Truth source"))
        stageRevision(
            revisionNumber = 1,
            channelIds = listOf(CHANNEL_A, CHANNEL_B),
        )
        activateRevision(revisionNumber = 1, channelCount = 2)
        stageRevision(
            revisionNumber = 2,
            channelIds = listOf(CHANNEL_B, CHANNEL_C),
        )

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PRIMARY_PROFILE,
                canonicalChannelId = CHANNEL_A,
                isFavorite = true,
            ),
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PRIMARY_PROFILE,
                canonicalChannelId = CHANNEL_B,
                isHidden = true,
            ),
        )

        insertActiveEpg()
        publishMatches(
            catalogRevisionNumber = 1,
            matches = mapOf(
                EXTERNAL_A to CHANNEL_A,
                EXTERNAL_B to CHANNEL_B,
            ),
        )

        listOf(PRIMARY_PROFILE, SECONDARY_PROFILE).forEach { profileId ->
            ALL_CHANNELS.forEachIndexed { index, channelId ->
                assertThat(
                    recent.recordSuccessfulPlayback(
                        profileId = profileId,
                        channelId = channelId,
                        successfulAtEpochMillis = 10_000L + index,
                    ),
                ).isEqualTo(RecentChannelWriteResult.Applied)
            }
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun playbackSearchRecentAndGuideShareTruthAcrossActiveRevisionSwap() = runTest {
        assertProfileMembership(
            profileId = PRIMARY_PROFILE,
            expectedChannelIds = setOf(CHANNEL_A),
        )
        assertProfileMembership(
            profileId = SECONDARY_PROFILE,
            expectedChannelIds = setOf(CHANNEL_A, CHANNEL_B),
        )
        assertDirectPlayback(
            profileId = PRIMARY_PROFILE,
            playableChannelIds = setOf(CHANNEL_A),
            unavailableChannelIds = setOf(CHANNEL_B, CHANNEL_C),
        )
        assertDirectPlayback(
            profileId = SECONDARY_PROFILE,
            playableChannelIds = setOf(CHANNEL_A, CHANNEL_B),
            unavailableChannelIds = setOf(CHANNEL_C),
        )
        assertRetainedRecentHistory()

        activateRevision(revisionNumber = 2, channelCount = 2)
        publishMatches(
            catalogRevisionNumber = 2,
            matches = mapOf(
                EXTERNAL_B to CHANNEL_B,
                EXTERNAL_C to CHANNEL_C,
            ),
        )

        assertProfileMembership(
            profileId = PRIMARY_PROFILE,
            expectedChannelIds = setOf(CHANNEL_C),
        )
        assertProfileMembership(
            profileId = SECONDARY_PROFILE,
            expectedChannelIds = setOf(CHANNEL_B, CHANNEL_C),
        )
        assertDirectPlayback(
            profileId = PRIMARY_PROFILE,
            playableChannelIds = setOf(CHANNEL_C),
            unavailableChannelIds = setOf(CHANNEL_A, CHANNEL_B),
        )
        assertDirectPlayback(
            profileId = SECONDARY_PROFILE,
            playableChannelIds = setOf(CHANNEL_B, CHANNEL_C),
            unavailableChannelIds = setOf(CHANNEL_A),
        )
        assertRetainedRecentHistory()
    }

    private suspend fun assertProfileMembership(
        profileId: String,
        expectedChannelIds: Set<String>,
    ) {
        val playbackIds = playback.observeChannels(
            ChannelQuery(profileId = profileId, limit = 10),
        ).first().map { channel -> channel.channelId }.toSet()

        val searchIds = search.observe(
            ChannelSearchQuery(
                profileId = profileId,
                text = SEARCH_TOKEN,
                nowEpochMillis = NOW,
                limit = 10,
            ),
        ).first().results.map { result -> result.channel.channelId }.toSet()

        val recentIds = recent.observeRecent(
            RecentChannelsQuery(profileId = profileId, limit = 10),
        ).first().map { item -> item.channel.channelId }.toSet()

        val readyGuideIds = guide.getNowNext(
            NowNextQuery(
                profileId = profileId,
                canonicalChannelIds = ALL_CHANNELS,
                nowEpochMillis = NOW,
            ),
        ).filter { projection -> projection.state == GuideProjectionState.READY }
            .map { projection -> projection.canonicalChannelId }
            .toSet()

        assertThat(playbackIds).containsExactlyElementsIn(expectedChannelIds)
        assertThat(searchIds).containsExactlyElementsIn(expectedChannelIds)
        assertThat(recentIds).containsExactlyElementsIn(expectedChannelIds)
        assertThat(readyGuideIds).containsExactlyElementsIn(expectedChannelIds)
    }

    private suspend fun assertDirectPlayback(
        profileId: String,
        playableChannelIds: Set<String>,
        unavailableChannelIds: Set<String>,
    ) {
        playableChannelIds.forEach { channelId ->
            assertThat(playback.getChannel(profileId, channelId)).isNotNull()
            assertThat(playback.resolveVariant(profileId, channelId))
                .isInstanceOf(PlaybackVariantResolution.Ready::class.java)
        }
        unavailableChannelIds.forEach { channelId ->
            assertThat(playback.getChannel(profileId, channelId)).isNull()
            assertThat(playback.resolveVariant(profileId, channelId)).isNull()
        }
    }

    private suspend fun assertRetainedRecentHistory() {
        assertThat(database.recentChannelsDao().countForProfile(PRIMARY_PROFILE)).isEqualTo(3)
        assertThat(database.recentChannelsDao().countForProfile(SECONDARY_PROFILE)).isEqualTo(3)
    }

    private suspend fun stageRevision(
        revisionNumber: Long,
        channelIds: List<String>,
    ) {
        sourceStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = revisionNumber * 1_000L,
        )
        sourceStore.stageBatch(
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
    }

    private suspend fun activateRevision(
        revisionNumber: Long,
        channelCount: Int,
    ) {
        assertThat(
            sourceStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = revisionNumber,
                activatedAtEpochMillis = revisionNumber * 1_000L + 500L,
                statistics = SourceRevisionStatistics(
                    parsedEntries = channelCount,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun stagedEntry(
        revisionNumber: Long,
        index: Int,
        channelId: String,
    ): StagedCatalogEntry {
        val externalId = externalId(channelId)
        return StagedCatalogEntry(
            providerChannelId = "provider-$revisionNumber-$channelId",
            providerKey = "tvg:$externalId",
            rawName = "Truth $channelId",
            canonicalChannelId = channelId,
            canonicalDisplayName = "Truth $channelId",
            streamVariantId = "variant-$revisionNumber-$channelId",
            locator = "https://example.invalid/$revisionNumber/$channelId.m3u8",
            tvgId = externalId,
            groupTitle = "Truth group",
            channelNumber = (index + 1).toString(),
        )
    }

    private suspend fun insertActiveEpg() {
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = EPG_SOURCE_ID,
                name = "Truth guide",
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
                acceptedChannels = ALL_CHANNELS.size,
                acceptedProgrammes = ALL_CHANNELS.size,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )
        database.epgRevisionDao().insertChannels(
            ALL_CHANNELS.map { channelId ->
                EpgChannelEntity(
                    sourceId = EPG_SOURCE_ID,
                    revisionNumber = EPG_REVISION,
                    externalId = externalId(channelId),
                    primaryDisplayName = "Guide $channelId",
                    primaryLanguage = "en",
                    iconRef = null,
                )
            },
        )
        database.epgRevisionDao().insertProgrammes(
            ALL_CHANNELS.mapIndexed { index, channelId ->
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

    private suspend fun publishMatches(
        catalogRevisionNumber: Long,
        matches: Map<String, String>,
    ) {
        val snapshot = requireNotNull(
            database.epgMatchingDao().relationSnapshot(EPG_SOURCE_ID),
        )
        assertThat(snapshot.catalogRevisionNumber).isEqualTo(catalogRevisionNumber)
        assertThat(
            database.epgMatchingDao().replaceIfCurrent(
                snapshot = snapshot,
                matches = ALL_CHANNELS.map { channelId ->
                    val externalId = externalId(channelId)
                    val canonicalChannelId = matches[externalId]
                    if (canonicalChannelId == null) {
                        EpgChannelMatchEntity(
                            epgSourceId = EPG_SOURCE_ID,
                            epgRevisionNumber = EPG_REVISION,
                            providerSourceId = SOURCE_ID,
                            catalogRevisionNumber = catalogRevisionNumber,
                            epgExternalChannelId = externalId,
                            decision = EpgChannelMatchDecision.UNRESOLVED.name,
                            reasonCode = EpgMatchReasonCode.NO_MATCH.name,
                            canonicalChannelId = null,
                            candidateCount = 0,
                        )
                    } else {
                        EpgChannelMatchEntity(
                            epgSourceId = EPG_SOURCE_ID,
                            epgRevisionNumber = EPG_REVISION,
                            providerSourceId = SOURCE_ID,
                            catalogRevisionNumber = catalogRevisionNumber,
                            epgExternalChannelId = externalId,
                            decision = EpgChannelMatchDecision.MATCHED.name,
                            reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                            canonicalChannelId = canonicalChannelId,
                            candidateCount = 1,
                        )
                    }
                },
            ),
        ).isEqualTo(EpgMatchPublicationResult.Applied)
    }

    private fun externalId(channelId: String): String = "epg-$channelId"

    private object SecurePlaybackAccessPolicyResolver : PlaybackAccessPolicyResolver {
        override suspend fun resolve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessDecision = PlaybackAccessDecision.SecureTransport

        override suspend fun approve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unchanged

        override suspend fun revoke(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unchanged

        override suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult =
            PlaybackAccessMutationResult.Unchanged
    }

    private companion object {
        const val PRIMARY_PROFILE = DatabaseDefaults.PRIMARY_PROFILE_ID
        const val SECONDARY_PROFILE = "profile-secondary"
        const val SOURCE_ID = "source-truth"
        const val EPG_SOURCE_ID = "epg-truth"
        const val EPG_REVISION = 1L
        const val CHANNEL_A = "channel-a"
        const val CHANNEL_B = "channel-b"
        const val CHANNEL_C = "channel-c"
        const val EXTERNAL_A = "epg-channel-a"
        const val EXTERNAL_B = "epg-channel-b"
        const val EXTERNAL_C = "epg-channel-c"
        const val SEARCH_TOKEN = "truth"
        const val NOW = 1_500L

        val ALL_CHANNELS = listOf(CHANNEL_A, CHANNEL_B, CHANNEL_C)
    }
}

package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.UnhandledPlaybackReferenceResolver
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackIntentCandidateResolutionTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var archiveResolver: RecordingArchiveResolver
    private lateinit var playbackCatalog: RoomPlaybackCatalog

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        archiveResolver = RecordingArchiveResolver()
        playbackCatalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = SecureAccessResolver,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
            playbackArchiveResolver = archiveResolver,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun catchupIntentResolvesExactlyTheCandidateChosenByRecovery() = runTest {
        seedTwoActiveVariants()
        archiveResolver.nextResolution = PlaybackArchiveResolution.Ready(
            locator = MATERIALIZED_SECOND_LOCATOR,
            timeline = TIMELINE,
        )
        val intent = PlaybackIntent.CatchupProgram(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            startEpochMillis = PROGRAMME_START,
            endEpochMillis = PROGRAMME_END,
        )

        val resolution = playbackCatalog.resolveIntentCandidate(
            profileId = PROFILE_ID,
            intent = intent,
            candidate = PlaybackCandidateIdentity(
                channelId = CHANNEL_ID,
                variantId = SECOND_VARIANT_ID,
            ),
        ) as PlaybackVariantResolution.Ready

        val archiveRequest = archiveResolver.requests.single()
        assertThat(archiveRequest.intent).isEqualTo(intent)
        assertThat(archiveRequest.livePlaybackReference).isEqualTo(SECOND_LIVE_LOCATOR)
        assertThat(archiveRequest.metadata.mode).isEqualTo("append")
        assertThat(archiveRequest.metadata.source).isEqualTo(SECOND_CATCHUP_SOURCE)
        assertThat(archiveRequest.metadata.days).isEqualTo(3)
        assertThat(archiveRequest.metadata.correction).isEqualTo("-1.0")
        assertThat(resolution.request.variantId).isEqualTo(SECOND_VARIANT_ID)
        assertThat(resolution.request.locator).isEqualTo(MATERIALIZED_SECOND_LOCATOR)
        assertThat(resolution.request.timeline).isEqualTo(TIMELINE)
    }

    private suspend fun seedTwoActiveVariants() {
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "M3U Provider",
                credentialRef = CREDENTIAL_REF,
            ),
        )
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            startedAtEpochMillis = 1_000L,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-first",
                    providerKey = "tvg:news:first",
                    rawName = "News First",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = FIRST_VARIANT_ID,
                    locator = FIRST_LIVE_LOCATOR,
                    catchupMode = "append",
                    catchupSource = FIRST_CATCHUP_SOURCE,
                    catchupDays = 7,
                    catchupCorrection = "+2.0",
                ),
                StagedCatalogEntry(
                    providerChannelId = "provider-second",
                    providerKey = "tvg:news:second",
                    rawName = "News Second",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = SECOND_VARIANT_ID,
                    locator = SECOND_LIVE_LOCATOR,
                    catchupMode = "append",
                    catchupSource = SECOND_CATCHUP_SOURCE,
                    catchupDays = 3,
                    catchupCorrection = "-1.0",
                ),
            ),
        )
        val activated = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 2_000L,
            statistics = SourceRevisionStatistics(
                parsedEntries = 2,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(activated).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private class RecordingArchiveResolver : PlaybackArchiveResolver {
        val requests = mutableListOf<PlaybackArchiveRequest>()
        var nextResolution: PlaybackArchiveResolution = PlaybackArchiveResolution.NotApplicable

        override fun resolve(request: PlaybackArchiveRequest): PlaybackArchiveResolution {
            requests += request
            return nextResolution
        }
    }

    private object SecureAccessResolver : PlaybackAccessPolicyResolver {
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
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-m3u"
        const val CHANNEL_ID = "channel-news"
        const val FIRST_VARIANT_ID = "variant-first"
        const val SECOND_VARIANT_ID = "variant-second"
        const val CREDENTIAL_REF = "credential-source-m3u"
        const val PROGRAMME_ID = "programme-revision-9-sequence-42"
        const val PROGRAMME_START = 1_799_985_600_000L
        const val PROGRAMME_END = PROGRAMME_START + 3_600_000L
        const val FIRST_LIVE_LOCATOR = "https://first.example/live.m3u8"
        const val SECOND_LIVE_LOCATOR = "https://second.example/live.m3u8"
        const val FIRST_CATCHUP_SOURCE = "?utc={utc}&source=first"
        const val SECOND_CATCHUP_SOURCE = "?utc={utc}&source=second"
        const val MATERIALIZED_SECOND_LOCATOR =
            "https://second.example/live.m3u8?utc=1799989200&source=second"

        val TIMELINE = ResolvedPlaybackTimeline(
            windowStartEpochMillis = 1_799_740_800_000L,
            windowEndEpochMillis = 1_800_000_000_000L,
            programmeStartEpochMillis = PROGRAMME_START,
            programmeEndEpochMillis = PROGRAMME_END,
            initialPositionEpochMillis = PROGRAMME_START,
            correctionMillis = -3_600_000L,
            granularityMillis = 1_000L,
            playAsLive = false,
        )
    }
}

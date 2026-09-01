package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackArchiveUnavailableReason
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
class PlaybackArchiveResolutionTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var accessResolver: RecordingAccessResolver
    private lateinit var archiveResolver: RecordingArchiveResolver
    private lateinit var playbackCatalog: RoomPlaybackCatalog

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        accessResolver = RecordingAccessResolver()
        archiveResolver = RecordingArchiveResolver()
        playbackCatalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = accessResolver,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
            playbackArchiveResolver = archiveResolver,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun catchupIntentMaterializesBeforeExistingAccessPolicyAndCarriesTimeline() = runTest {
        insertProfile()
        activateCatchupSource()
        archiveResolver.nextResolution = PlaybackArchiveResolution.Ready(
            locator = MATERIALIZED_LOCATOR,
            timeline = TIMELINE,
        )

        val resolution = playbackCatalog.resolveIntent(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.CatchupPosition(
                channelId = CHANNEL_ID,
                positionEpochMillis = POSITION_EPOCH_MILLIS,
            ),
            preferredVariantId = VARIANT_ID,
        )

        val ready = resolution as PlaybackVariantResolution.Ready
        assertThat(archiveResolver.requests).hasSize(1)
        val archiveRequest = archiveResolver.requests.single()
        assertThat(archiveRequest.intent).isEqualTo(
            PlaybackIntent.CatchupPosition(
                channelId = CHANNEL_ID,
                positionEpochMillis = POSITION_EPOCH_MILLIS,
            ),
        )
        assertThat(archiveRequest.livePlaybackReference).isEqualTo(LIVE_LOCATOR)
        assertThat(archiveRequest.metadata).isEqualTo(
            PlaybackArchiveMetadata(
                mode = "append",
                source = CATCHUP_SOURCE,
                days = 7,
                correction = "+2.0",
            ),
        )

        assertThat(accessResolver.lastCredentialRef).isEqualTo(CREDENTIAL_REF)
        assertThat(accessResolver.lastLocator).isEqualTo(MATERIALIZED_LOCATOR)
        assertThat(ready.request.locator).isEqualTo(MATERIALIZED_LOCATOR)
        assertThat(ready.request.timeline).isEqualTo(TIMELINE)
        assertThat(ready.request.toString()).doesNotContain(LIVE_SECRET)
        assertThat(ready.request.toString()).doesNotContain(CATCHUP_SECRET)
        assertThat(ready.request.toString()).doesNotContain(CREDENTIAL_REF)
        assertThat(archiveRequest.toString()).doesNotContain(LIVE_SECRET)
        assertThat(archiveRequest.toString()).doesNotContain(CATCHUP_SECRET)
    }

    @Test
    fun existingLiveResolutionDoesNotInvokeArchiveResolver() = runTest {
        insertProfile()
        activateCatchupSource()

        val resolution = playbackCatalog.resolveVariant(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            preferredVariantId = VARIANT_ID,
        )

        val ready = resolution as PlaybackVariantResolution.Ready
        assertThat(archiveResolver.requests).isEmpty()
        assertThat(accessResolver.lastLocator).isEqualTo(LIVE_LOCATOR)
        assertThat(ready.request.locator).isEqualTo(LIVE_LOCATOR)
        assertThat(ready.request.timeline).isNull()
    }

    @Test
    fun typedArchiveUnavailableStopsBeforeAccessPolicy() = runTest {
        insertProfile()
        activateCatchupSource()
        archiveResolver.nextResolution = PlaybackArchiveResolution.Unavailable(
            PlaybackArchiveUnavailableReason.OutsideRetention,
        )

        val resolution = playbackCatalog.resolveIntent(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.CatchupPosition(
                channelId = CHANNEL_ID,
                positionEpochMillis = POSITION_EPOCH_MILLIS,
            ),
            preferredVariantId = VARIANT_ID,
        )

        assertThat(resolution).isEqualTo(
            PlaybackVariantResolution.AccessUnavailable(
                app.muxtv.catalog.PlaybackAccessUnavailableReason.ArchiveOutsideRetention,
            ),
        )
        assertThat(accessResolver.lastLocator).isNull()
    }

    private suspend fun insertProfile() {
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
    }

    private suspend fun activateCatchupSource() {
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
                    providerChannelId = "provider-news",
                    providerKey = "tvg:news",
                    rawName = "News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = VARIANT_ID,
                    locator = LIVE_LOCATOR,
                    catchupMode = "append",
                    catchupSource = CATCHUP_SOURCE,
                    catchupDays = 7,
                    catchupCorrection = "+2.0",
                ),
            ),
        )
        val result = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 2_000L,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(result).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private class RecordingArchiveResolver : PlaybackArchiveResolver {
        val requests = mutableListOf<PlaybackArchiveRequest>()
        var nextResolution: PlaybackArchiveResolution = PlaybackArchiveResolution.NotApplicable

        override fun resolve(request: PlaybackArchiveRequest): PlaybackArchiveResolution {
            requests += request
            return nextResolution
        }
    }

    private class RecordingAccessResolver : PlaybackAccessPolicyResolver {
        var lastCredentialRef: String? = null
        var lastLocator: String? = null

        override suspend fun resolve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessDecision {
            lastCredentialRef = credentialRef
            lastLocator = playbackLocator
            return PlaybackAccessDecision.SecureTransport
        }

        override suspend fun approve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Applied

        override suspend fun revoke(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Applied

        override suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult =
            PlaybackAccessMutationResult.Applied
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-m3u"
        const val CHANNEL_ID = "channel-news"
        const val VARIANT_ID = "variant-news"
        const val CREDENTIAL_REF = "credential-source-m3u"
        const val LIVE_SECRET = "TEST_LIVE_SECRET"
        const val CATCHUP_SECRET = "TEST_CATCHUP_SECRET"
        const val LIVE_LOCATOR = "http://archive.example/live.m3u8?token=$LIVE_SECRET"
        const val CATCHUP_SOURCE = "&utc={utc}&archiveToken=$CATCHUP_SECRET"
        const val MATERIALIZED_LOCATOR =
            "http://archive.example/live.m3u8?token=$LIVE_SECRET&utc=1799989200&archiveToken=$CATCHUP_SECRET"
        const val POSITION_EPOCH_MILLIS = 1_799_989_200_000L

        val TIMELINE = ResolvedPlaybackTimeline(
            windowStartEpochMillis = 1_799_395_200_000L,
            windowEndEpochMillis = 1_800_000_000_000L,
            programmeStartEpochMillis = null,
            programmeEndEpochMillis = null,
            initialPositionEpochMillis = POSITION_EPOCH_MILLIS,
            correctionMillis = 2 * 60 * 60 * 1_000L,
            granularityMillis = 1_000L,
            playAsLive = false,
        )
    }
}

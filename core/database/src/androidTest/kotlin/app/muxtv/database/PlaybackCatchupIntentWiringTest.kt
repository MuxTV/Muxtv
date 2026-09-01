package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackArchiveUnavailableReason
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackIntentResolution
import app.muxtv.catalog.PlaybackIntentTransportRequest
import app.muxtv.catalog.PlaybackIntentTransportResolution
import app.muxtv.catalog.PlaybackIntentTransportResolver
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
class PlaybackCatchupIntentWiringTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun catchupIntentMaterializesBeforeExistingCredentialBoundAccessPolicy() = runTest {
        insertProfile()
        activateCatchupVariant()

        val transportRequests = mutableListOf<PlaybackIntentTransportRequest>()
        val accessPolicy = RecordingAccessPolicy()
        val timeline = ResolvedPlaybackTimeline(
            windowStartEpochMillis = POSITION_EPOCH_MILLIS - 60_000L,
            windowEndEpochMillis = POSITION_EPOCH_MILLIS + 60_000L,
            programmeStartEpochMillis = null,
            programmeEndEpochMillis = null,
            initialPositionEpochMillis = POSITION_EPOCH_MILLIS,
            correctionMillis = 0L,
            granularityMillis = 1_000L,
            playAsLive = false,
        )
        val catalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = accessPolicy,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
            playbackIntentTransportResolver = PlaybackIntentTransportResolver { request ->
                transportRequests += request
                PlaybackIntentTransportResolution.Ready(
                    locator = ARCHIVE_LOCATOR,
                    timeline = timeline,
                )
            },
        )
        val intent = PlaybackIntent.CatchupPosition(
            channelId = CHANNEL_ID,
            positionEpochMillis = POSITION_EPOCH_MILLIS,
        )

        val resolution = catalog.resolveIntent(
            profileId = PROFILE_ID,
            candidate = PlaybackCandidateIdentity(CHANNEL_ID, VARIANT_ID),
            intent = intent,
        )

        assertThat(resolution).isInstanceOf(PlaybackIntentResolution.Ready::class.java)
        val ready = resolution as PlaybackIntentResolution.Ready
        assertThat(ready.request.locator).isEqualTo(ARCHIVE_LOCATOR)
        assertThat(ready.timeline).isEqualTo(timeline)
        assertThat(ready.request.variantId).isEqualTo(VARIANT_ID)

        assertThat(transportRequests).hasSize(1)
        val transportRequest = transportRequests.single()
        assertThat(transportRequest.intent).isEqualTo(intent)
        assertThat(transportRequest.liveLocator).isEqualTo(LIVE_LOCATOR)
        assertThat(transportRequest.archiveMetadata.mode).isEqualTo("append")
        assertThat(transportRequest.archiveMetadata.source).isEqualTo(CATCHUP_SOURCE)
        assertThat(transportRequest.archiveMetadata.days).isEqualTo(7)
        assertThat(transportRequest.archiveMetadata.correction).isEqualTo("+2.0")

        assertThat(accessPolicy.resolutions).containsExactly(CREDENTIAL_REF to ARCHIVE_LOCATOR)
        assertThat(accessPolicy.resolutions.single().second).isNotEqualTo(LIVE_LOCATOR)

        assertThat(transportRequest.toString()).doesNotContain(LIVE_SECRET)
        assertThat(transportRequest.toString()).doesNotContain(ARCHIVE_SECRET)
        assertThat(ready.toString()).doesNotContain(ARCHIVE_SECRET)
    }

    @Test
    fun liveIntentCannotSelectArchiveMaterialization() = runTest {
        insertProfile()
        activateCatchupVariant()

        var materializationCalls = 0
        val accessPolicy = RecordingAccessPolicy()
        val catalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = accessPolicy,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
            playbackIntentTransportResolver = PlaybackIntentTransportResolver {
                materializationCalls += 1
                PlaybackIntentTransportResolution.Unavailable(
                    PlaybackArchiveUnavailableReason.INVALID_METADATA,
                )
            },
        )

        val resolution = catalog.resolveIntent(
            profileId = PROFILE_ID,
            candidate = PlaybackCandidateIdentity(CHANNEL_ID, VARIANT_ID),
            intent = PlaybackIntent.Live(CHANNEL_ID),
        )

        assertThat(resolution).isEqualTo(PlaybackIntentResolution.NotApplicable)
        assertThat(materializationCalls).isEqualTo(0)
        assertThat(accessPolicy.resolutions).isEmpty()
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

    private suspend fun activateCatchupVariant() {
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "M3U Provider",
                credentialRef = CREDENTIAL_REF,
            ),
        )
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            startedAtEpochMillis = 1_000L,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = PROVIDER_CHANNEL_ID,
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
        assertThat(
            revisionStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = 1L,
                activatedAtEpochMillis = 2_000L,
                statistics = SourceRevisionStatistics(
                    parsedEntries = 1,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private class RecordingAccessPolicy : PlaybackAccessPolicyResolver {
        val resolutions = mutableListOf<Pair<String, String>>()

        override suspend fun resolve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessDecision {
            resolutions += credentialRef to playbackLocator
            return PlaybackAccessDecision.SecureTransport
        }

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
        const val PROVIDER_CHANNEL_ID = "provider-news"
        const val CHANNEL_ID = "channel-news"
        const val VARIANT_ID = "variant-news"
        const val CREDENTIAL_REF = "00000000-0000-0000-0000-000000000285"
        const val LIVE_SECRET = "TEST_LIVE_SECRET_285"
        const val ARCHIVE_SECRET = "TEST_ARCHIVE_SECRET_285"
        const val LIVE_LOCATOR = "https://streams.invalid/live.m3u8?token=$LIVE_SECRET"
        const val CATCHUP_SOURCE = "?utc={utc}&token=$ARCHIVE_SECRET"
        const val ARCHIVE_LOCATOR =
            "https://streams.invalid/live.m3u8?token=$LIVE_SECRET&utc=1699999999&token=$ARCHIVE_SECRET"
        const val POSITION_EPOCH_MILLIS = 1_700_000_000_000L
    }
}

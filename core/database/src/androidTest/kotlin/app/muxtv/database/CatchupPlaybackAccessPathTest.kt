package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatchupPlaybackAccessPathTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var accessPolicy: RecordingAccessPolicy
    private lateinit var referenceRequests: MutableList<PlaybackReferenceRequest>
    private lateinit var playbackCatalog: RoomPlaybackCatalog

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        accessPolicy = RecordingAccessPolicy()
        referenceRequests = mutableListOf()
        playbackCatalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = accessPolicy,
            playbackReferenceResolver = PlaybackReferenceResolver { request ->
                referenceRequests += request
                if (request.intent is PlaybackIntent.CatchupPosition) {
                    PlaybackReferenceResolution.MaterializedDirect(
                        locator = ARCHIVE_LOCATOR,
                        timeline = TIMELINE,
                    )
                } else {
                    PlaybackReferenceResolution.Unhandled
                }
            },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun catchupIntentCarriesPersistedMetadataThroughReferenceResolutionThenExistingAccessPolicy() =
        runTest {
            seedActiveCatchupVariant()
            val intent = PlaybackIntent.CatchupPosition(
                channelId = CHANNEL_ID,
                positionEpochMillis = REQUEST_POSITION,
            )

            val resolution = playbackCatalog.resolveIntent(
                profileId = PROFILE_ID,
                intent = intent,
                preferredVariantId = VARIANT_ID,
            ) as PlaybackVariantResolution.Ready

            val reference = referenceRequests.single()
            assertThat(reference.intent).isEqualTo(intent)
            assertThat(reference.catchupMetadata).isNotNull()
            assertThat(reference.catchupMetadata?.mode).isEqualTo("append")
            assertThat(reference.catchupMetadata?.sourceTemplate).isEqualTo(CATCHUP_SOURCE)
            assertThat(reference.catchupMetadata?.retentionDays).isEqualTo(7)
            assertThat(reference.catchupMetadata?.correction).isEqualTo("+2.0")
            assertThat(reference.toString()).doesNotContain(CATCHUP_SECRET)
            assertThat(reference.toString()).doesNotContain(LIVE_SECRET)

            assertThat(accessPolicy.lastCredentialRef).isEqualTo(CREDENTIAL_REF)
            assertThat(accessPolicy.lastLocator).isEqualTo(ARCHIVE_LOCATOR)
            assertThat(resolution.request.locator).isEqualTo(ARCHIVE_LOCATOR)
            assertThat(resolution.timeline).isEqualTo(TIMELINE)
            assertThat(resolution.request.toString()).doesNotContain(ARCHIVE_SECRET)
        }

    @Test
    fun liveIntentKeepsLegacyDirectResolutionWithoutCatchupContext() = runTest {
        seedActiveCatchupVariant()

        val resolution = playbackCatalog.resolveIntent(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.Live(CHANNEL_ID),
            preferredVariantId = VARIANT_ID,
        ) as PlaybackVariantResolution.Ready

        val reference = referenceRequests.single()
        assertThat(reference.intent).isNull()
        assertThat(reference.catchupMetadata).isNull()
        assertThat(accessPolicy.lastLocator).isEqualTo(LIVE_LOCATOR)
        assertThat(resolution.request.locator).isEqualTo(LIVE_LOCATOR)
        assertThat(resolution.timeline).isNull()
    }

    private suspend fun seedActiveCatchupVariant() {
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
                name = "M3U source",
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
                    providerKey = "m3u:catchup-news",
                    rawName = "Catch-up News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "Catch-up News",
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
        const val SOURCE_ID = "source-m3u-catchup"
        const val CREDENTIAL_REF = "credential-m3u-catchup"
        const val PROVIDER_CHANNEL_ID = "provider-catchup-news"
        const val CHANNEL_ID = "channel-catchup-news"
        const val VARIANT_ID = "variant-catchup-news"
        const val LIVE_SECRET = "TEST_LIVE_GLUE_SECRET"
        const val CATCHUP_SECRET = "TEST_CATCHUP_GLUE_SECRET"
        const val ARCHIVE_SECRET = "TEST_ARCHIVE_GLUE_SECRET"
        const val LIVE_LOCATOR = "https://streams.invalid/live/news.m3u8?token=$LIVE_SECRET"
        const val CATCHUP_SOURCE = "?utc={utc}&token=$CATCHUP_SECRET"
        const val ARCHIVE_LOCATOR = "https://streams.invalid/live/news.m3u8?token=$ARCHIVE_SECRET"
        const val REQUEST_POSITION = 1_799_992_800_000L
        val TIMELINE = ResolvedPlaybackTimeline(
            windowStartEpochMillis = 1_799_395_200_000L,
            windowEndEpochMillis = 1_800_000_000_000L,
            programmeStartEpochMillis = null,
            programmeEndEpochMillis = null,
            initialPositionEpochMillis = REQUEST_POSITION,
            correctionMillis = 7_200_000L,
            granularityMillis = 1_000L,
            playAsLive = false,
        )
    }
}

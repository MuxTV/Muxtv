package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.UnhandledPlaybackReferenceResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackCatalogStaleVariantTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var accessResolver: RecordingAccessResolver
    private lateinit var playbackCatalog: RoomPlaybackCatalog

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        accessResolver = RecordingAccessResolver()
        playbackCatalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = accessResolver,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun stalePreferredVariantNeverFallsBackToAnotherActiveStreamForResolutionOrApproval() = runTest {
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
                name = "Provider",
                credentialRef = CREDENTIAL_REF,
            ),
        )
        revisionStore.beginRevision(SOURCE_ID, revisionNumber = 1, startedAtEpochMillis = 1_000)
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-channel",
                    providerKey = "tvg:news",
                    rawName = "News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = ACTIVE_VARIANT_ID,
                    locator = "http://active.example/live.m3u8?token=secret",
                ),
            ),
        )
        assertThat(
            revisionStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                activatedAtEpochMillis = 2_000,
                statistics = SourceRevisionStatistics(
                    parsedEntries = 1,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)

        val resolution = playbackCatalog.resolveVariant(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            preferredVariantId = STALE_VARIANT_ID,
        )
        val approval = playbackCatalog.approveInsecurePlayback(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            variantId = STALE_VARIANT_ID,
        )

        assertThat(resolution).isNull()
        assertThat(approval).isEqualTo(PlaybackAccessMutationResult.NotFound)
        assertThat(accessResolver.resolveCalls).isEqualTo(0)
        assertThat(accessResolver.mutationCalls).isEqualTo(0)
    }

    private class RecordingAccessResolver : PlaybackAccessPolicyResolver {
        var resolveCalls: Int = 0
        var mutationCalls: Int = 0

        override suspend fun resolve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessDecision {
            resolveCalls += 1
            return PlaybackAccessDecision.ApprovalRequired("http://active.example:80")
        }

        override suspend fun approve(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessMutationResult {
            mutationCalls += 1
            return PlaybackAccessMutationResult.Applied
        }

        override suspend fun revoke(
            credentialRef: String,
            playbackLocator: String,
        ): PlaybackAccessMutationResult {
            mutationCalls += 1
            return PlaybackAccessMutationResult.Applied
        }

        override suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult {
            mutationCalls += 1
            return PlaybackAccessMutationResult.Applied
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-provider"
        const val CHANNEL_ID = "channel-news"
        const val ACTIVE_VARIANT_ID = "variant-active"
        const val STALE_VARIANT_ID = "variant-stale"
        const val CREDENTIAL_REF = "00000000-0000-0000-0000-000000000042"
    }
}

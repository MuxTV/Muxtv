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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceBoundPlaybackOwnershipTest {
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
    fun preferredVariantUsesItsOwnSourceCredentialWhenOpaqueProviderIdentityMatches() = runTest {
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
        activateSource(
            sourceId = SOURCE_A,
            credentialRef = CREDENTIAL_A,
            providerChannelId = "provider-a-42",
            variantId = VARIANT_A,
        )
        activateSource(
            sourceId = SOURCE_B,
            credentialRef = CREDENTIAL_B,
            providerChannelId = "provider-b-42",
            variantId = VARIANT_B,
        )

        val referenceRequests = mutableListOf<PlaybackReferenceRequest>()
        val catalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = SecureAccessPolicy,
            playbackReferenceResolver = PlaybackReferenceResolver { request ->
                referenceRequests += request
                PlaybackReferenceResolution.Ready(
                    locator = "https://materialized.example/live/42.ts",
                    insecureHttpPreapproved = false,
                )
            },
        )

        val resolution = catalog.resolveVariant(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            preferredVariantId = VARIANT_B,
        )

        assertThat(resolution).isInstanceOf(PlaybackVariantResolution.Ready::class.java)
        assertThat(referenceRequests).hasSize(1)
        assertThat(referenceRequests.single().credentialRef).isEqualTo(CREDENTIAL_B)
        assertThat(referenceRequests.single().playbackReference).isEqualTo(SHARED_OPAQUE_REFERENCE)
        assertThat(referenceRequests.single().credentialRef).isNotEqualTo(CREDENTIAL_A)
        assertThat((resolution as PlaybackVariantResolution.Ready).request.variantId).isEqualTo(VARIANT_B)
    }

    private suspend fun activateSource(
        sourceId: String,
        credentialRef: String,
        providerChannelId: String,
        variantId: String,
    ) {
        revisionStore.upsertSource(
            SourceDefinition(
                id = sourceId,
                name = sourceId,
                credentialRef = credentialRef,
            ),
        )
        revisionStore.beginRevision(sourceId, revisionNumber = 1L, startedAtEpochMillis = 1_000L)
        revisionStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = 1L,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = providerChannelId,
                    providerKey = "xtream:42",
                    rawName = "Shared News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "Shared News",
                    streamVariantId = variantId,
                    locator = SHARED_OPAQUE_REFERENCE,
                ),
            ),
        )
        assertThat(
            revisionStore.activate(
                sourceId = sourceId,
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

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val CHANNEL_ID = "canonical-shared-news"
        const val SOURCE_A = "source-a"
        const val SOURCE_B = "source-b"
        const val CREDENTIAL_A = "00000000-0000-0000-0000-0000000000a1"
        const val CREDENTIAL_B = "00000000-0000-0000-0000-0000000000b2"
        const val VARIANT_A = "variant-a-42"
        const val VARIANT_B = "variant-b-42"
        const val SHARED_OPAQUE_REFERENCE = "muxtv-provider://xtream/live/42/ts"
    }
}

private object SecureAccessPolicy : PlaybackAccessPolicyResolver {
    override suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessDecision = PlaybackAccessDecision.SecureTransport

    override suspend fun validateMaterializedTransport(
        playbackLocator: String,
        insecureHttpPreapproved: Boolean,
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

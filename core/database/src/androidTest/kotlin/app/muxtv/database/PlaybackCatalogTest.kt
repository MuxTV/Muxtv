package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackCatalogTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var playbackCatalog: RoomPlaybackCatalog

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        playbackCatalog = RoomPlaybackCatalog(database.playbackCatalogDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activeCatalogCombinesVariantsAndAppliesProfileOverlay() = runTest {
        insertProfile()
        activateSource(
            sourceId = "source-a",
            sourceName = "Provider A",
            providerChannelId = "provider-a",
            variantId = "variant-a",
            locator = "https://a.example/live?token=secret-a",
        )
        activateSource(
            sourceId = "source-b",
            sourceName = "Provider B",
            providerChannelId = "provider-b",
            variantId = "variant-b",
            locator = "https://b.example/live?token=secret-b",
        )
        stageWithoutActivation(
            sourceId = "source-a",
            revisionNumber = 2,
            channelId = "staging-only-channel",
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isFavorite = true,
                customName = "My News",
                channelNumber = 7,
            ),
        )

        val channels = playbackCatalog.observeChannels(
            ChannelQuery(
                profileId = PROFILE_ID,
                searchText = "my news",
                favoritesOnly = true,
            ),
        ).first()

        assertThat(channels).hasSize(1)
        assertThat(channels.single().channelId).isEqualTo(CHANNEL_ID)
        assertThat(channels.single().displayName).isEqualTo("My News")
        assertThat(channels.single().channelNumber).isEqualTo("7")
        assertThat(channels.single().isFavorite).isTrue()
        assertThat(channels.single().variantCount).isEqualTo(2)

        val stagingSearch = playbackCatalog.observeChannels(
            ChannelQuery(profileId = PROFILE_ID, searchText = "staging only"),
        ).first()
        assertThat(stagingSearch).isEmpty()
    }

    @Test
    fun preferredVariantResolvesHeadersWithoutLeakingSecretsInToString() = runTest {
        insertProfile()
        activateSource(
            sourceId = "source-a",
            sourceName = "Provider A",
            providerChannelId = "provider-a",
            variantId = "variant-a",
            locator = "https://a.example/live?token=secret-a",
        )
        activateSource(
            sourceId = "source-b",
            sourceName = "Provider B",
            providerChannelId = "provider-b",
            variantId = "variant-b",
            locator = "https://b.example/live?token=secret-b",
            userAgent = "Secret Agent",
            referrer = "https://portal.example/private",
        )

        val request = playbackCatalog.resolveVariant(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            preferredVariantId = "variant-b",
        )

        assertThat(request?.variantId).isEqualTo("variant-b")
        assertThat(request?.requestHeaders).containsExactly(
            "User-Agent", "Secret Agent",
            "Referer", "https://portal.example/private",
        )
        assertThat(request.toString()).doesNotContain("secret-b")
        assertThat(request.toString()).doesNotContain("Secret Agent")
        assertThat(request.toString()).doesNotContain("portal.example")
        assertThat(request.toString()).contains("locator=<redacted>")
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

    private suspend fun activateSource(
        sourceId: String,
        sourceName: String,
        providerChannelId: String,
        variantId: String,
        locator: String,
        userAgent: String? = null,
        referrer: String? = null,
    ) {
        revisionStore.upsertSource(SourceDefinition(id = sourceId, name = sourceName))
        revisionStore.beginRevision(sourceId, revisionNumber = 1, startedAtEpochMillis = 1_000)
        revisionStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = 1,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = providerChannelId,
                    providerKey = "tvg:news",
                    rawName = "News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = variantId,
                    locator = locator,
                    tvgId = "news",
                    logoUrl = "https://images.example/news.png",
                    groupTitle = "Information",
                    channelNumber = "10",
                    userAgent = userAgent,
                    referrer = referrer,
                ),
            ),
        )
        val result = revisionStore.activate(
            sourceId = sourceId,
            revisionNumber = 1,
            activatedAtEpochMillis = 2_000,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(result).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private suspend fun stageWithoutActivation(
        sourceId: String,
        revisionNumber: Long,
        channelId: String,
    ) {
        revisionStore.beginRevision(sourceId, revisionNumber, startedAtEpochMillis = 3_000)
        revisionStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-staging",
                    providerKey = "name:staging",
                    rawName = "Staging Only",
                    canonicalChannelId = channelId,
                    canonicalDisplayName = "Staging Only",
                    streamVariantId = "variant-staging",
                    locator = "https://staging.example/live",
                ),
            ),
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val CHANNEL_ID = "channel-news"
    }
}

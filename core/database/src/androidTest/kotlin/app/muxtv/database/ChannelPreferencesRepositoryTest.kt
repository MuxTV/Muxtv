package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.RejectAllPlaybackAccessPolicyResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelPreferencesRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var playbackCatalog: RoomPlaybackCatalog
    private lateinit var channelPreferences: RoomChannelPreferencesRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        playbackCatalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = RejectAllPlaybackAccessPolicyResolver,
        )
        channelPreferences = RoomChannelPreferencesRepository(database.channelPreferencesDao())
        insertProfile()
        activateChannel()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun favoriteMutationCreatesOverlayAndDrivesFavoritesQuery() = runTest {
        val initial = playbackCatalog.observeChannels(
            ChannelQuery(profileId = PROFILE_ID),
        ).first().single()
        assertThat(initial.isFavorite).isFalse()

        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelFavoriteMutationResult.Applied)

        val favorites = playbackCatalog.observeChannels(
            ChannelQuery(profileId = PROFILE_ID, favoritesOnly = true),
        ).first()
        assertThat(favorites).hasSize(1)
        assertThat(favorites.single().channelId).isEqualTo(CHANNEL_ID)
        assertThat(favorites.single().isFavorite).isTrue()

        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelFavoriteMutationResult.Unchanged)

        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, CHANNEL_ID, false),
        ).isEqualTo(ChannelFavoriteMutationResult.Applied)
        assertThat(
            playbackCatalog.observeChannels(
                ChannelQuery(profileId = PROFILE_ID, favoritesOnly = true),
            ).first(),
        ).isEmpty()
    }

    @Test
    fun favoriteMutationPreservesExistingOverlayCustomization() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isFavorite = false,
                customName = "My News",
                channelNumber = 7,
            ),
        )

        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelFavoriteMutationResult.Applied)

        val channel = requireNotNull(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID))
        assertThat(channel.summary.isFavorite).isTrue()
        assertThat(channel.summary.displayName).isEqualTo("My News")
        assertThat(channel.summary.channelNumber).isEqualTo("7")
    }

    @Test
    fun missingChannelReturnsNotFoundWithoutCreatingOverlay() = runTest {
        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, "missing-channel", true),
        ).isEqualTo(ChannelFavoriteMutationResult.NotFound)
        assertThat(database.catalogDao().countOverlays(PROFILE_ID)).isEqualTo(0)
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

    private suspend fun activateChannel() {
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Provider",
            ),
        )
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            startedAtEpochMillis = 1_000,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = PROVIDER_CHANNEL_ID,
                    providerKey = "tvg:news",
                    rawName = "News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = VARIANT_ID,
                    locator = "https://example.invalid/news.m3u8",
                    tvgId = "news",
                    groupTitle = "Information",
                    channelNumber = "10",
                ),
            ),
        )
        val activation = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 2_000,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(activation).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-a"
        const val CHANNEL_ID = "canonical-news"
        const val PROVIDER_CHANNEL_ID = "provider-news"
        const val VARIANT_ID = "variant-news"
    }
}

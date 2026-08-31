package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.RejectAllPlaybackAccessPolicyResolver
import app.muxtv.catalog.UnhandledPlaybackReferenceResolver
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
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
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
    fun hiddenMutationCreatesOverlayAndSupportsUnhide() = runTest {
        assertThat(
            channelPreferences.setHidden(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelPreferenceMutationResult.Applied)
        assertThat(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID)).isNull()

        assertThat(
            channelPreferences.setHidden(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelPreferenceMutationResult.Unchanged)

        assertThat(
            channelPreferences.setHidden(PROFILE_ID, CHANNEL_ID, false),
        ).isEqualTo(ChannelPreferenceMutationResult.Applied)
        assertThat(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID)).isNotNull()
    }

    @Test
    fun customNameMutationTrimsAndPreservesOtherOverlayFields() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isFavorite = true,
                channelNumber = 7,
            ),
        )

        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, CHANNEL_ID, "  My News  "),
        ).isEqualTo(ChannelPreferenceMutationResult.Applied)

        val channel = requireNotNull(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID))
        assertThat(channel.summary.displayName).isEqualTo("My News")
        assertThat(channel.summary.channelNumber).isEqualTo("7")
        assertThat(channel.summary.isFavorite).isTrue()

        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, CHANNEL_ID, "My News"),
        ).isEqualTo(ChannelPreferenceMutationResult.Unchanged)
    }

    @Test
    fun customNameMutationRejectsInvalidInputWithoutCreatingOverlay() = runTest {
        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, CHANNEL_ID, "   "),
        ).isEqualTo(ChannelPreferenceMutationResult.InvalidInput)
        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, CHANNEL_ID, "News\u0000HD"),
        ).isEqualTo(ChannelPreferenceMutationResult.InvalidInput)
        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, CHANNEL_ID, "N".repeat(129)),
        ).isEqualTo(ChannelPreferenceMutationResult.InvalidInput)
        assertThat(database.catalogDao().countOverlays(PROFILE_ID)).isEqualTo(0)
    }

    @Test
    fun channelNumberMutationPersistsValidNumberAndSupportsReset() = runTest {
        assertThat(
            channelPreferences.setChannelNumber(PROFILE_ID, CHANNEL_ID, 7),
        ).isEqualTo(ChannelPreferenceMutationResult.Applied)
        assertThat(
            requireNotNull(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID)).summary.channelNumber,
        ).isEqualTo("7")

        assertThat(
            channelPreferences.setChannelNumber(PROFILE_ID, CHANNEL_ID, 0),
        ).isEqualTo(ChannelPreferenceMutationResult.InvalidInput)
        assertThat(
            channelPreferences.setChannelNumber(PROFILE_ID, CHANNEL_ID, 10_000),
        ).isEqualTo(ChannelPreferenceMutationResult.InvalidInput)

        assertThat(
            channelPreferences.setChannelNumber(PROFILE_ID, CHANNEL_ID, null),
        ).isEqualTo(ChannelPreferenceMutationResult.Applied)
        assertThat(
            requireNotNull(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID)).summary.channelNumber,
        ).isEqualTo("10")
    }

    @Test
    fun resetCustomizationRestoresProviderPresentationAndPreservesFavorite() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isFavorite = true,
                isHidden = true,
                customName = "Hidden News",
                channelNumber = 77,
            ),
        )

        assertThat(
            channelPreferences.resetCustomization(PROFILE_ID, CHANNEL_ID),
        ).isEqualTo(ChannelPreferenceMutationResult.Applied)

        val channel = requireNotNull(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID))
        assertThat(channel.summary.displayName).isEqualTo("News")
        assertThat(channel.summary.channelNumber).isEqualTo("10")
        assertThat(channel.summary.isFavorite).isTrue()

        assertThat(
            channelPreferences.resetCustomization(PROFILE_ID, CHANNEL_ID),
        ).isEqualTo(ChannelPreferenceMutationResult.Unchanged)
    }

    @Test
    fun missingChannelReturnsNotFoundWithoutCreatingOverlay() = runTest {
        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, "missing-channel", true),
        ).isEqualTo(ChannelFavoriteMutationResult.NotFound)
        assertThat(
            channelPreferences.setHidden(PROFILE_ID, "missing-channel", true),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, "missing-channel", "Missing"),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(
            channelPreferences.setChannelNumber(PROFILE_ID, "missing-channel", 1),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(
            channelPreferences.resetCustomization(PROFILE_ID, "missing-channel"),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(database.catalogDao().countOverlays(PROFILE_ID)).isEqualTo(0)
    }

    @Test
    fun hiddenChannelReturnsNotFoundWithoutChangingExistingOverlay() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isFavorite = false,
                isHidden = true,
                customName = "Hidden News",
                channelNumber = 77,
            ),
        )

        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelFavoriteMutationResult.NotFound)
        assertThat(database.catalogDao().countOverlays(PROFILE_ID)).isEqualTo(1)
        assertThat(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID)).isNull()
    }

    @Test
    fun inactiveChannelReturnsNotFoundWithoutCreatingOverlay() = runTest {
        activateReplacementRevision()

        assertThat(playbackCatalog.getChannel(PROFILE_ID, CHANNEL_ID)).isNull()
        assertThat(
            channelPreferences.setFavorite(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelFavoriteMutationResult.NotFound)
        assertThat(
            channelPreferences.setHidden(PROFILE_ID, CHANNEL_ID, true),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(
            channelPreferences.setCustomName(PROFILE_ID, CHANNEL_ID, "Old News"),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(
            channelPreferences.setChannelNumber(PROFILE_ID, CHANNEL_ID, 7),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
        assertThat(
            channelPreferences.resetCustomization(PROFILE_ID, CHANNEL_ID),
        ).isEqualTo(ChannelPreferenceMutationResult.NotFound)
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
                stagedEntry(
                    providerChannelId = PROVIDER_CHANNEL_ID,
                    canonicalChannelId = CHANNEL_ID,
                    streamVariantId = VARIANT_ID,
                    providerKey = "tvg:news",
                    rawName = "News",
                    canonicalDisplayName = "News",
                    locator = "https://example.invalid/news.m3u8",
                ),
            ),
        )
        activateRevision(revisionNumber = 1, activatedAtEpochMillis = 2_000)
    }

    private suspend fun activateReplacementRevision() {
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 2,
            startedAtEpochMillis = 3_000,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 2,
            startedAtEpochMillis = 3_000,
            entries = listOf(
                stagedEntry(
                    providerChannelId = "provider-replacement",
                    canonicalChannelId = "canonical-replacement",
                    streamVariantId = "variant-replacement",
                    providerKey = "tvg:replacement",
                    rawName = "Replacement",
                    canonicalDisplayName = "Replacement",
                    locator = "https://example.invalid/replacement.m3u8",
                ),
            ),
        )
        activateRevision(revisionNumber = 2, activatedAtEpochMillis = 4_000)
    }

    private suspend fun activateRevision(
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
    ) {
        val activation = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = activatedAtEpochMillis,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(activation).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun stagedEntry(
        providerChannelId: String,
        canonicalChannelId: String,
        streamVariantId: String,
        providerKey: String,
        rawName: String,
        canonicalDisplayName: String,
        locator: String,
    ) = StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = providerKey,
        rawName = rawName,
        canonicalChannelId = canonicalChannelId,
        canonicalDisplayName = canonicalDisplayName,
        streamVariantId = streamVariantId,
        locator = locator,
        tvgId = providerKey.removePrefix("tvg:"),
        groupTitle = "Information",
        channelNumber = "10",
    )

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-a"
        const val CHANNEL_ID = "canonical-news"
        const val PROVIDER_CHANNEL_ID = "provider-news"
        const val VARIANT_ID = "variant-news"
    }
}

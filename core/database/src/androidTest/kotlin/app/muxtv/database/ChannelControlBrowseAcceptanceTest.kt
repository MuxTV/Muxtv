package app.muxtv.database

import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelControlBrowseAcceptanceTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var browse: RoomChannelBrowseRepository
    private lateinit var recent: RoomRecentChannelsRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        browse = RoomChannelBrowseRepository(
            dao = database.channelBrowseDao(),
            guideRepository = RoomEpgGuideRepository(database.epgGuideDao()),
            nowEpochMillis = { 5_000L },
        )
        recent = RoomRecentChannelsRepository(database.recentChannelsDao())
        database.profileDao().insert(ProfileEntity(PROFILE_ID, "Primary", isPrimary = true))
        activateCatalog()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun oneOverlayDrivesAllFavoritesAndRecentThenHideSuppressesEveryBrowseProjection() = runTest {
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_A,
                isFavorite = true,
                customName = "Мои новости",
                channelNumber = 77,
            ),
        )
        recent.recordSuccessfulPlayback(PROFILE_ID, CHANNEL_B, 1_000L)
        recent.recordSuccessfulPlayback(PROFILE_ID, CHANNEL_A, 2_000L)

        val all = browse.pages(query(ChannelBrowseFilter.ALL)).asSnapshot()
        val favorites = browse.pages(query(ChannelBrowseFilter.FAVORITES)).asSnapshot()
        val recentRows = browse.pages(query(ChannelBrowseFilter.RECENT)).asSnapshot()

        assertThat(all.map { it.channelId }).containsExactly(CHANNEL_B, CHANNEL_A).inOrder()
        assertEffectiveOverlay(all.single { it.channelId == CHANNEL_A })
        assertThat(favorites.map { it.channelId }).containsExactly(CHANNEL_A)
        assertEffectiveOverlay(favorites.single())
        assertThat(recentRows.map { it.channelId }).containsExactly(CHANNEL_A, CHANNEL_B).inOrder()
        assertEffectiveOverlay(recentRows.first())

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_A,
                isFavorite = true,
                customName = "Мои новости",
                channelNumber = 77,
                isHidden = true,
            ),
        )

        assertThat(browse.pages(query(ChannelBrowseFilter.ALL)).asSnapshot().map { it.channelId })
            .containsExactly(CHANNEL_B)
        assertThat(browse.pages(query(ChannelBrowseFilter.FAVORITES)).asSnapshot()).isEmpty()
        assertThat(browse.pages(query(ChannelBrowseFilter.RECENT)).asSnapshot().map { it.channelId })
            .containsExactly(CHANNEL_B)
    }

    private fun assertEffectiveOverlay(item: app.muxtv.catalog.ChannelBrowseItem) {
        assertThat(item.displayName).isEqualTo("Мои новости")
        assertThat(item.channelNumber).isEqualTo("77")
        assertThat(item.isFavorite).isTrue()
    }

    private fun query(filter: ChannelBrowseFilter) = ChannelBrowseQuery(PROFILE_ID, filter)

    private suspend fun activateCatalog() {
        revisionStore.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            startedAtEpochMillis = 1_000L,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            entries = listOf(
                stagedEntry(
                    providerChannelId = "provider-a",
                    canonicalChannelId = CHANNEL_A,
                    displayName = "Новости",
                    channelNumber = "1",
                    variantId = "variant-a",
                ),
                stagedEntry(
                    providerChannelId = "provider-b",
                    canonicalChannelId = CHANNEL_B,
                    displayName = "Спорт",
                    channelNumber = "2",
                    variantId = "variant-b",
                ),
            ),
        )
        val result = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            activatedAtEpochMillis = 1_500L,
            statistics = SourceRevisionStatistics(
                parsedEntries = 2,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(result).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun stagedEntry(
        providerChannelId: String,
        canonicalChannelId: String,
        displayName: String,
        channelNumber: String,
        variantId: String,
    ) = StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = "tvg:$canonicalChannelId",
        rawName = displayName,
        canonicalChannelId = canonicalChannelId,
        canonicalDisplayName = displayName,
        streamVariantId = variantId,
        locator = "https://example.invalid/$variantId.m3u8",
        tvgId = canonicalChannelId,
        groupTitle = "Тест",
        channelNumber = channelNumber,
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val SOURCE_ID = "source-main"
        const val CHANNEL_A = "channel-a"
        const val CHANNEL_B = "channel-b"
    }
}

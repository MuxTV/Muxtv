package app.muxtv.database

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelManagementInvalidationTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        database.profileDao().insert(ProfileEntity(PROFILE_ID, "Primary", isPrimary = true))
        activateCatalog()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun overlayChangeInvalidatesManagementPagingSource() = runTest {
        val source = database.channelBrowseDao().pageManagedChannels(
            profileId = PROFILE_ID,
            hiddenState = null,
        )
        val first = source.load(refresh()) as PagingSource.LoadResult.Page
        assertThat(first.data.single().isHidden).isFalse()

        val invalidated = CompletableDeferred<Unit>()
        source.registerInvalidatedCallback { invalidated.complete(Unit) }

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isHidden = true,
            ),
        )
        database.invalidationTracker.refresh("user_channel_overlays")

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000L) { invalidated.await() }
        }
        assertThat(source.invalid).isTrue()

        val replacement = database.channelBrowseDao().pageManagedChannels(
            profileId = PROFILE_ID,
            hiddenState = null,
        )
        val second = replacement.load(refresh()) as PagingSource.LoadResult.Page
        assertThat(second.data.single().isHidden).isTrue()
    }

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
                StagedCatalogEntry(
                    providerChannelId = "provider-channel-1",
                    providerKey = "tvg:1",
                    rawName = "Channel 1",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "Channel 1",
                    streamVariantId = "variant-1",
                    locator = "https://example.invalid/1.m3u8",
                    channelNumber = "1",
                ),
            ),
        )
        revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            activatedAtEpochMillis = 1_500L,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
    }

    private fun refresh() = PagingSource.LoadParams.Refresh<Int>(
        key = null,
        loadSize = 64,
        placeholdersEnabled = false,
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val SOURCE_ID = "source-main"
        const val CHANNEL_ID = "channel-00001"
    }
}

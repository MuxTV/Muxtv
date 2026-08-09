package app.muxtv.database

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@CatalogDatabaseMeasurement
class ChannelBrowseLargeCatalogMeasurementTest {
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
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun channelFiftyThousandIsReachableWithoutLoadingWholeCatalog() = runTest {
        revisionStore.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
        revisionStore.beginRevision(SOURCE_ID, 1L, 1_000L)
        (1..50_000).chunked(500).forEach { indexes ->
            revisionStore.stageBatch(
                sourceId = SOURCE_ID,
                revisionNumber = 1L,
                entries = indexes.map(::stagedEntry),
            )
        }
        revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            activatedAtEpochMillis = 2_000L,
            statistics = SourceRevisionStatistics(50_000, 0, 0),
        )

        val source = database.channelBrowseDao().pageActiveChannels(PROFILE_ID, false)
        val page = source.load(
            PagingSource.LoadParams.Refresh(
                key = 49_936,
                loadSize = 64,
                placeholdersEnabled = false,
            ),
        ) as PagingSource.LoadResult.Page

        assertThat(page.data).hasSize(64)
        assertThat(page.data.last().channelId).isEqualTo("channel-50000")
    }

    private fun stagedEntry(index: Int): StagedCatalogEntry {
        val suffix = index.toString().padStart(5, '0')
        return StagedCatalogEntry(
            providerChannelId = "provider-$suffix",
            providerKey = "tvg:$suffix",
            rawName = "Channel $suffix",
            canonicalChannelId = "channel-$suffix",
            canonicalDisplayName = "Channel $suffix",
            streamVariantId = "variant-$suffix",
            locator = "https://example.invalid/$suffix.m3u8",
            channelNumber = index.toString(),
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val SOURCE_ID = "source-main"
    }
}

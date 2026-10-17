package app.muxtv.database

import android.os.SystemClock
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
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class ChannelBrowseLargeCatalogTest {
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
    // Large-corpus correctness is not a one-minute benchmark. Room v11 dual-write expands
    // the write workload; capture phase timings separately for performance qualification.
    fun channelFiftyThousandIsReachableWithoutLoadingWholeCatalog() = runTest(timeout = 5.minutes) {
        val startedAt = SystemClock.elapsedRealtime()
        revisionStore.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
        revisionStore.beginRevision(SOURCE_ID, 1L, 1_000L)
        (1..50_000).chunked(500).forEachIndexed { batchIndex, indexes ->
            revisionStore.stageBatch(
                sourceId = SOURCE_ID,
                revisionNumber = 1L,
                entries = indexes.map(::stagedEntry),
            )
            if ((batchIndex + 1) % 20 == 0) {
                println(
                    "largeCatalogReachability: staged=${(batchIndex + 1) * 500} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
        }
        val stageElapsedMs = SystemClock.elapsedRealtime() - startedAt
        val activation = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            activatedAtEpochMillis = 2_000L,
            statistics = SourceRevisionStatistics(50_000, 0, 0),
        )
        assertThat(activation).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
        val activationElapsedMs = SystemClock.elapsedRealtime() - startedAt - stageElapsedMs

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
        val pageElapsedMs =
            SystemClock.elapsedRealtime() - startedAt - stageElapsedMs - activationElapsedMs
        println(
            "largeCatalogReachability: entries=50000 stageMs=$stageElapsedMs " +
                "activateMs=$activationElapsedMs lastPageMs=$pageElapsedMs",
        )
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

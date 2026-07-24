package app.muxtv.database

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
class CatalogStagingAtomicityTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun streamVariantConflictRollsBackWholeCatalogBatch() = runTest {
        store.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Atomic source",
            ),
        )
        store.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = REVISION_NUMBER,
            startedAtEpochMillis = 1_000,
        )

        val failure = runCatching {
            store.stageBatch(
                sourceId = SOURCE_ID,
                revisionNumber = REVISION_NUMBER,
                entries = listOf(
                    stagedEntry(
                        providerChannelId = "provider-1",
                        canonicalChannelId = "canonical-1",
                    ),
                    stagedEntry(
                        providerChannelId = "provider-2",
                        canonicalChannelId = "canonical-2",
                    ),
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(
            database.sourceRevisionDao().countRevisionEntries(
                sourceId = SOURCE_ID,
                revisionNumber = REVISION_NUMBER,
            ),
        ).isEqualTo(0)
    }

    private fun stagedEntry(
        providerChannelId: String,
        canonicalChannelId: String,
    ): StagedCatalogEntry = StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = "key-$providerChannelId",
        rawName = providerChannelId,
        canonicalChannelId = canonicalChannelId,
        canonicalDisplayName = providerChannelId,
        streamVariantId = DUPLICATE_STREAM_VARIANT_ID,
        locator = "https://stream.example/$providerChannelId",
    )

    private companion object {
        const val SOURCE_ID = "source-atomic"
        const val REVISION_NUMBER = 1L
        const val DUPLICATE_STREAM_VARIANT_ID = "variant-duplicate"
    }
}

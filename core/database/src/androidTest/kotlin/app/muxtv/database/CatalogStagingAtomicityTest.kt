package app.muxtv.database

import androidx.room3.Room
import androidx.room3.useReaderConnection
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
    fun streamVariantConflictRollsBackCanonicalProviderAndVariantRows() = runTest {
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
        assertThat(
            database.querySingleLong(
                """
                SELECT COUNT(*)
                FROM canonical_channels
                WHERE id IN ('canonical-1', 'canonical-2')
                """.trimIndent(),
            ),
        ).isEqualTo(0L)
        assertThat(
            database.querySingleLong(
                """
                SELECT COUNT(*)
                FROM stream_variants
                WHERE id = '$DUPLICATE_STREAM_VARIANT_ID'
                """.trimIndent(),
            ),
        ).isEqualTo(0L)
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

    private suspend fun MuxTvDatabase.querySingleLong(sql: String): Long =
        useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                check(statement.step()) { "Count query returned no row." }
                statement.getLong(0)
            }
        }

    private companion object {
        const val SOURCE_ID = "source-atomic"
        const val REVISION_NUMBER = 1L
        const val DUPLICATE_STREAM_VARIANT_ID = "variant-duplicate"
    }
}

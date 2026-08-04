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
class SearchCanonicalRowIdStabilityTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        sourceStore.upsertSource(SourceDefinition(SOURCE, "Source", credentialRef = null))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun canonicalSearchDocumentKeepsRowIdWhenPublishedNameChanges() = runTest {
        stageAndActivate(revision = 1, name = "Первое имя")
        val documentKey = "canonical-name:$CHANNEL"
        val firstRowId = requireNotNull(database.searchIndexDao().rowIdForDocumentKey(documentKey))
        assertThat(database.searchIndexDao().textsForCanonicalKind(CHANNEL, SearchDocumentKind.CANONICAL_NAME))
            .containsExactly("Первое имя")

        stageAndActivate(revision = 2, name = "Второе имя")
        val secondRowId = requireNotNull(database.searchIndexDao().rowIdForDocumentKey(documentKey))

        assertThat(secondRowId).isEqualTo(firstRowId)
        assertThat(database.searchIndexDao().textsForCanonicalKind(CHANNEL, SearchDocumentKind.CANONICAL_NAME))
            .containsExactly("Второе имя")
    }

    private suspend fun stageAndActivate(revision: Long, name: String) {
        sourceStore.beginRevision(SOURCE, revision, startedAtEpochMillis = 10 + revision)
        sourceStore.stageBatch(
            sourceId = SOURCE,
            revisionNumber = revision,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-$revision",
                    providerKey = "provider-key-$revision",
                    rawName = name,
                    canonicalChannelId = CHANNEL,
                    canonicalDisplayName = name,
                    streamVariantId = "variant-$revision",
                    locator = "https://example.invalid/$revision",
                    tvgName = name,
                ),
            ),
        )
        val result = sourceStore.activate(
            sourceId = SOURCE,
            revisionNumber = revision,
            activatedAtEpochMillis = 20 + revision,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(result).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private companion object {
        const val SOURCE = "rowid-source"
        const val CHANNEL = "rowid-channel"
    }
}

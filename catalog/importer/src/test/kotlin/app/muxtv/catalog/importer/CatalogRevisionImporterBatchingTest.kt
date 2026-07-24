package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.database.InactiveSourceRemovalResult
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.SourceRevisionStore
import app.muxtv.database.StagedCatalogEntry
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogRevisionImporterBatchingTest {
    @Test
    fun handsOffFullBatchAndFinalRemainderWithoutReusingEitherList() = runTest {
        val store = RecordingSourceRevisionStore()
        val importer = CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = store,
            nowEpochMillis = { 1_000 },
        )

        val result = importer.import(
            request = CatalogImportRequest(
                sourceId = "source-batching",
                sourceName = "Batching",
            ),
            input = ByteArrayInputStream(playlist(entryCount = 251).toByteArray()),
        )

        assertThat(result).isInstanceOf(CatalogImportResult.Imported::class.java)
        assertThat(store.batches.map { it.size }).containsExactly(250, 1).inOrder()
        assertThat(store.batches[0] === store.batches[1]).isFalse()
        assertThat(store.batches[0].first().rawName).isEqualTo("Channel 1")
        assertThat(store.batches[0].last().rawName).isEqualTo("Channel 250")
        assertThat(store.batches[1].single().rawName).isEqualTo("Channel 251")
    }

    private fun playlist(entryCount: Int): String = buildString {
        appendLine("#EXTM3U")
        for (index in 1..entryCount) {
            appendLine("#EXTINF:-1 tvg-id=\"channel-$index\",Channel $index")
            appendLine("https://stream.example/$index")
        }
    }

    private class RecordingSourceRevisionStore : SourceRevisionStore {
        val batches = mutableListOf<List<StagedCatalogEntry>>()

        override suspend fun upsertSource(source: SourceDefinition) = Unit

        override suspend fun nextRevisionNumber(sourceId: String): Long = 1

        override suspend fun beginRevision(
            sourceId: String,
            revisionNumber: Long,
            startedAtEpochMillis: Long,
        ) = Unit

        override suspend fun stageBatch(
            sourceId: String,
            revisionNumber: Long,
            entries: List<StagedCatalogEntry>,
        ) {
            batches += entries
        }

        override suspend fun activate(
            sourceId: String,
            revisionNumber: Long,
            activatedAtEpochMillis: Long,
            statistics: SourceRevisionStatistics,
        ): SourceRevisionActivationResult = SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = 0,
            entryCount = batches.sumOf { it.size },
        )

        override suspend fun discard(sourceId: String, revisionNumber: Long) = Unit

        override suspend fun removeInactiveSource(
            sourceId: String,
            expectedCredentialRef: String,
        ): InactiveSourceRemovalResult = InactiveSourceRemovalResult.NotFound
    }
}

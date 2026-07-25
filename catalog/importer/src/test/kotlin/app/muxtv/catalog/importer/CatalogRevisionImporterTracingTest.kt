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

class CatalogRevisionImporterTracingTest {
    @Test
    fun unavailablePlatformTracingDoesNotChangeImportResult() = runTest {
        val store = RecordingSourceRevisionStore()
        val importer = CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = store,
            nowEpochMillis = { 1_000 },
        )

        val result = importer.import(
            request = CatalogImportRequest(
                sourceId = "source-tracing",
                sourceName = "Tracing",
            ),
            input = ByteArrayInputStream(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="channel-1",Channel 1
                https://stream.example/1
                """.trimIndent().toByteArray(),
            ),
        )

        assertThat(result).isInstanceOf(CatalogImportResult.Imported::class.java)
        assertThat(store.stagedEntries).hasSize(1)
    }

    private class RecordingSourceRevisionStore : SourceRevisionStore {
        val stagedEntries = mutableListOf<StagedCatalogEntry>()

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
            stagedEntries += entries
        }

        override suspend fun activate(
            sourceId: String,
            revisionNumber: Long,
            activatedAtEpochMillis: Long,
            statistics: SourceRevisionStatistics,
        ): SourceRevisionActivationResult = SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = 0,
            entryCount = stagedEntries.size,
        )

        override suspend fun discard(sourceId: String, revisionNumber: Long) = Unit

        override suspend fun removeInactiveSource(
            sourceId: String,
            expectedCredentialRef: String,
        ): InactiveSourceRemovalResult = InactiveSourceRemovalResult.NotFound
    }
}

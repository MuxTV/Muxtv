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
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogRevisionImporterCleanupCancellationTest {
    @Test
    fun cleanupCancellationIsNotConvertedToStorageFailure() = runTest {
        val cancellation = CancellationException("discard cancelled")
        val importer = CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = FailingStageStore(cancellation),
            nowEpochMillis = { 1_000L },
        )

        val actual = try {
            importer.import(
                request = CatalogImportRequest(
                    sourceId = "source-cancel-cleanup",
                    sourceName = "Cancellation",
                ),
                input = ByteArrayInputStream(
                    "#EXTM3U\n#EXTINF:-1,Channel\nhttps://stream.example/live\n".toByteArray(),
                ),
            )
            error("CancellationException must be rethrown.")
        } catch (error: CancellationException) {
            error
        }

        assertThat(actual).isSameInstanceAs(cancellation)
    }
}

private class FailingStageStore(
    private val discardCancellation: CancellationException,
) : SourceRevisionStore {
    override suspend fun upsertSource(source: SourceDefinition) = Unit

    override suspend fun nextRevisionNumber(sourceId: String): Long = 1L

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
        throw IOException("stage failed")
    }

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = error("Activation must not run.")

    override suspend fun discard(
        sourceId: String,
        revisionNumber: Long,
    ) {
        throw discardCancellation
    }

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult = InactiveSourceRemovalResult.NotFound
}

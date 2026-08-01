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

class CatalogRevisionImporterOwnershipTest {
    @Test
    fun `source-owned import keeps metadata ownership and normal activation`() = runTest {
        val store = RecordingSourceRevisionStore()
        val importer = importer(store)

        val result = importer.import(
            request = request(),
            input = playlist(),
        )

        assertThat(result).isInstanceOf(CatalogImportResult.Imported::class.java)
        assertThat(store.sources).containsExactly(
            SourceDefinition(
                id = SOURCE_ID,
                name = SOURCE_NAME,
                credentialRef = CREDENTIAL_A,
            ),
        )
        assertThat(store.normalActivations).containsExactly(1L)
        assertThat(store.guardedCredentialRefs).isEmpty()
        assertThat(store.guardedRunTokens).isEmpty()
    }

    @Test
    fun `remote-bound import never rewrites metadata and guards credential plus lease`() = runTest {
        val store = RecordingSourceRevisionStore()
        val importer = importer(store)

        val result = importer.import(
            request = request(
                sourceOwnership = CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING,
                refreshRunToken = RUN_TOKEN,
            ),
            input = playlist(),
        )

        assertThat(result).isInstanceOf(CatalogImportResult.Imported::class.java)
        assertThat(store.sources).isEmpty()
        assertThat(store.normalActivations).isEmpty()
        assertThat(store.guardedCredentialRefs).containsExactly(CREDENTIAL_A)
        assertThat(store.guardedRunTokens).containsExactly(RUN_TOKEN)
    }

    @Test
    fun `superseded remote activation is typed and staging is discarded`() = runTest {
        val store = RecordingSourceRevisionStore(
            activationResult = SourceRevisionActivationResult.Superseded,
        )
        val importer = importer(store)

        val result = importer.import(
            request = request(
                sourceOwnership = CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING,
                refreshRunToken = RUN_TOKEN,
            ),
            input = playlist(),
        )

        assertThat(result).isEqualTo(CatalogImportResult.Superseded)
        assertThat(store.discardedRevisions).contains(1L)
    }

    @Test
    fun `remote request diagnostics do not expose credential or lease values`() {
        val request = request(
            sourceOwnership = CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING,
            refreshRunToken = RUN_TOKEN,
        )

        val text = request.toString()

        assertThat(text).doesNotContain(CREDENTIAL_A)
        assertThat(text).doesNotContain(RUN_TOKEN)
        assertThat(text).doesNotContain(SOURCE_ID)
        assertThat(text).doesNotContain(SOURCE_NAME)
    }

    private fun importer(store: SourceRevisionStore): CatalogRevisionImporter {
        var now = 0L
        return CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = store,
            nowEpochMillis = { ++now },
        )
    }

    private fun request(
        sourceOwnership: CatalogImportSourceOwnership = CatalogImportSourceOwnership.UPSERT_METADATA,
        refreshRunToken: String? = null,
    ): CatalogImportRequest = CatalogImportRequest(
        sourceId = SOURCE_ID,
        sourceName = SOURCE_NAME,
        credentialRef = CREDENTIAL_A,
        refreshRunToken = refreshRunToken,
        sourceOwnership = sourceOwnership,
    )

    private fun playlist(): ByteArrayInputStream = ByteArrayInputStream(
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="one" tvg-name="One",One
        https://example.invalid/live/one.m3u8
        """.trimIndent().toByteArray(),
    )

    private companion object {
        const val SOURCE_ID = "source-ownership"
        const val SOURCE_NAME = "Ownership source"
        const val CREDENTIAL_A = "00000000-0000-0000-0000-000000000076"
        const val RUN_TOKEN = "source-refresh-run-76"
    }
}

private class RecordingSourceRevisionStore(
    private val activationResult: SourceRevisionActivationResult =
        SourceRevisionActivationResult.Activated(
            revisionNumber = 1,
            previousRevisionNumber = 0,
            entryCount = 1,
        ),
) : SourceRevisionStore {
    val sources = mutableListOf<SourceDefinition>()
    val normalActivations = mutableListOf<Long>()
    val guardedCredentialRefs = mutableListOf<String>()
    val guardedRunTokens = mutableListOf<String>()
    val discardedRevisions = mutableListOf<Long>()

    override suspend fun upsertSource(source: SourceDefinition) {
        sources += source
    }

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
    ) = Unit

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        normalActivations += revisionNumber
        return activationResult
    }

    override suspend fun activateIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        guardedCredentialRefs += expectedCredentialRef
        return activationResult
    }

    override suspend fun activateIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        guardedCredentialRefs += expectedCredentialRef
        guardedRunTokens += expectedRunToken
        return activationResult
    }

    override suspend fun discard(sourceId: String, revisionNumber: Long) {
        discardedRevisions += revisionNumber
    }

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult = InactiveSourceRemovalResult.ConcurrentChange
}

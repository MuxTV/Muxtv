package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.catalog.ingest.StreamingXtreamParser
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.InactiveSourceRemovalResult
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.SourceRevisionStore
import app.muxtv.database.StagedCatalogEntry
import app.muxtv.network.MuxTvHttpClients
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test

class XtreamLiveRefresherHttpStatusContractTest {
    @Test
    fun `auth http failure stays typed as http and never starts a revision`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .body("synthetic unavailable")
                    .build(),
            )

            val credentialId = CredentialId.parse("00000000-0000-0000-0000-000000000230")
            val credentialStore = HttpStatusCredentialStore()
            val accessManager = XtreamSourceAccessManager(credentialStore)
            assertThat(
                accessManager.save(
                    credentialId,
                    XtreamSourceAccess(
                        baseUrl = server.url("/").toString(),
                        username = "TEST_HTTP_USER",
                        password = "TEST_HTTP_PASS",
                        insecureHttpApproved = true,
                    ),
                ),
            ).isEqualTo(CredentialWriteResult.Stored)

            val revisionStore = UntouchedRevisionStore()
            val refresher = XtreamLiveRefresher(
                accessManager = accessManager,
                importer = CatalogRevisionImporter(
                    parser = StreamingM3uParser(),
                    revisionStore = revisionStore,
                    nowEpochMillis = { 1L },
                ),
                sourceClient = MuxTvHttpClients().source,
                parser = StreamingXtreamParser(),
            )

            val result = refresher.refresh(
                XtreamLiveRefreshRequest(
                    sourceId = "source-http-status",
                    sourceName = "Synthetic Xtream HTTP status",
                    accessCredentialId = credentialId,
                ),
            )

            assertThat(result).isEqualTo(XtreamLiveRefreshResult.HttpFailure(statusCode = 503))
            assertThat(revisionStore.touched).isFalse()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
}

private class HttpStatusCredentialStore : CredentialStore {
    private val records = mutableMapOf<CredentialId, ByteArray>()

    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult {
        records[id] = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult {
        val bytes = records[id] ?: return CredentialReadResult.NotFound
        return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
    }

    override suspend fun remove(id: CredentialId): CredentialRemoveResult =
        if (records.remove(id) != null) CredentialRemoveResult.Removed else CredentialRemoveResult.NotFound

    override suspend fun reset(): CredentialResetResult {
        records.clear()
        return CredentialResetResult.Reset
    }
}

private class UntouchedRevisionStore : SourceRevisionStore {
    var touched: Boolean = false
        private set

    override suspend fun upsertSource(source: SourceDefinition) {
        touched = true
    }

    override suspend fun nextRevisionNumber(sourceId: String): Long {
        touched = true
        return 1L
    }

    override suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) {
        touched = true
    }

    override suspend fun stageBatch(
        sourceId: String,
        revisionNumber: Long,
        entries: List<StagedCatalogEntry>,
    ) {
        touched = true
    }

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        touched = true
        return SourceRevisionActivationResult.EmptyRevisionRejected
    }

    override suspend fun activateIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        touched = true
        return SourceRevisionActivationResult.EmptyRevisionRejected
    }

    override suspend fun activateIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        touched = true
        return SourceRevisionActivationResult.EmptyRevisionRejected
    }

    override suspend fun discard(
        sourceId: String,
        revisionNumber: Long,
    ) {
        touched = true
    }

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult {
        touched = true
        return InactiveSourceRemovalResult.ConcurrentChange
    }
}

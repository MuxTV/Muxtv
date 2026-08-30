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

class XtreamLiveOwnershipBindingContractTest {
    @Test
    fun `guarded Xtream import persists the supplied typed access reference`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(AUTH_ACTIVE).build())
            server.enqueue(MockResponse.Builder().body(LIVE_BASIC).build())

            val credentialStore = OwnershipCredentialStore()
            val accessManager = XtreamSourceAccessManager(credentialStore)
            assertThat(
                accessManager.save(
                    CREDENTIAL_ID,
                    XtreamSourceAccess(
                        baseUrl = server.url("/").toString(),
                        username = USERNAME,
                        password = PASSWORD,
                        insecureHttpApproved = true,
                    ),
                ),
            ).isEqualTo(CredentialWriteResult.Stored)

            val revisionStore = OwnershipRevisionStore()
            val refresher = XtreamLiveRefresher(
                accessManager = accessManager,
                importer = CatalogRevisionImporter(
                    parser = StreamingM3uParser(),
                    revisionStore = revisionStore,
                    nowEpochMillis = ownershipSequenceClock(10, 20, 30, 40),
                ),
                sourceClient = MuxTvHttpClients().source,
                parser = StreamingXtreamParser(),
            )
            val accessReference = SourceAccessReference.xtream(CREDENTIAL_ID)

            val result = refresher.refresh(
                XtreamLiveRefreshRequest(
                    sourceId = SOURCE_ID,
                    sourceName = SOURCE_NAME,
                    accessCredentialId = CREDENTIAL_ID,
                    accessReference = accessReference,
                    refreshRunToken = RUN_TOKEN,
                ),
            )

            assertThat(result).isInstanceOf(XtreamLiveRefreshResult.Refreshed::class.java)
            assertThat(revisionStore.guardedCredentialRef).isEqualTo(accessReference.value)
            assertThat(revisionStore.guardedCredentialRef).doesNotContain(USERNAME)
            assertThat(revisionStore.guardedCredentialRef).doesNotContain(PASSWORD)
        }
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000225",
        )
        const val SOURCE_ID = "source-xtream-owned"
        const val SOURCE_NAME = "Owned Xtream Live"
        const val RUN_TOKEN = "xtream-owned-run-224"
        const val USERNAME = "OWNERSHIP_USER_224"
        const val PASSWORD = "OWNERSHIP_PASS_224"
        const val AUTH_ACTIVE =
            "{\"user_info\":{\"auth\":1,\"status\":\"Active\",\"allowed_output_formats\":[\"ts\"]}}"
        const val LIVE_BASIC =
            "[{\"name\":\"Owned Live\",\"stream_type\":\"live\",\"stream_id\":808}]"
    }
}

private fun ownershipSequenceClock(vararg values: Long): () -> Long {
    val iterator = values.iterator()
    return { iterator.nextLong() }
}

private class OwnershipCredentialStore : CredentialStore {
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

private class OwnershipRevisionStore : SourceRevisionStore {
    var guardedCredentialRef: String? = null

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
    ) = Unit

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = SourceRevisionActivationResult.Activated(
        revisionNumber = revisionNumber,
        previousRevisionNumber = 0L,
        entryCount = statistics.parsedEntries,
    )

    override suspend fun activateIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = SourceRevisionActivationResult.Activated(
        revisionNumber = revisionNumber,
        previousRevisionNumber = 0L,
        entryCount = statistics.parsedEntries,
    )

    override suspend fun activateIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        guardedCredentialRef = expectedCredentialRef
        return SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = 0L,
            entryCount = statistics.parsedEntries,
        )
    }

    override suspend fun discard(
        sourceId: String,
        revisionNumber: Long,
    ) = Unit

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult = InactiveSourceRemovalResult.NotFound
}

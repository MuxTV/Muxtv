package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.ingest.StreamingM3uParser
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

class RemoteSourceRefresherOwnershipTest {
    @Test
    fun `durable refresh forwards lease and does not rewrite source metadata`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(PLAYLIST).build())
            val fixture = fixture(server)

            val result = fixture.refresher.refresh(request(refreshRunToken = RUN_TOKEN))

            assertThat(result).isInstanceOf(RemoteSourceRefreshResult.Refreshed::class.java)
            assertThat(fixture.revisionStore.sources).isEmpty()
            assertThat(fixture.revisionStore.guardedCredentialRefs).containsExactly(CREDENTIAL_ID.value)
            assertThat(fixture.revisionStore.guardedRunTokens).containsExactly(RUN_TOKEN)
        }
    }

    @Test
    fun `onboarding refresh without lease retains metadata ownership`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(PLAYLIST).build())
            val fixture = fixture(server)

            val result = fixture.refresher.refresh(request())

            assertThat(result).isInstanceOf(RemoteSourceRefreshResult.Refreshed::class.java)
            assertThat(fixture.revisionStore.sources).hasSize(1)
            assertThat(fixture.revisionStore.normalActivations).containsExactly(1L)
            assertThat(fixture.revisionStore.guardedRunTokens).isEmpty()
        }
    }

    @Test
    fun `superseded durable publication remains a typed terminal result`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(PLAYLIST).build())
            val fixture = fixture(
                server = server,
                activationResult = SourceRevisionActivationResult.Superseded,
            )

            val result = fixture.refresher.refresh(request(refreshRunToken = RUN_TOKEN))

            assertThat(result).isEqualTo(RemoteSourceRefreshResult.Superseded)
            assertThat(fixture.revisionStore.discardedRevisions).contains(1L)
        }
    }

    @Test
    fun `request diagnostics never expose source credential or lease values`() {
        val request = request(refreshRunToken = RUN_TOKEN)

        val text = request.toString()

        assertThat(text).doesNotContain(SOURCE_ID)
        assertThat(text).doesNotContain(SOURCE_NAME)
        assertThat(text).doesNotContain(CREDENTIAL_ID.value)
        assertThat(text).doesNotContain(RUN_TOKEN)
    }

    private suspend fun fixture(
        server: MockWebServer,
        activationResult: SourceRevisionActivationResult = SourceRevisionActivationResult.Activated(
            revisionNumber = 1,
            previousRevisionNumber = 0,
            entryCount = 1,
        ),
    ): Fixture {
        val credentialStore = SourceOwnershipCredentialStore()
        val accessManager = RemoteSourceAccessManager(credentialStore)
        assertThat(
            accessManager.save(
                CREDENTIAL_ID,
                RemoteSourceAccess(
                    url = server.url("/playlist.m3u8").toString(),
                    insecureHttpApproved = true,
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)
        val revisionStore = RefreshOwnershipRevisionStore(activationResult)
        val importer = CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = revisionStore,
            nowEpochMillis = sequenceClock(10, 20, 30, 40),
        )
        return Fixture(
            refresher = RemoteSourceRefresher(
                accessManager = accessManager,
                importer = importer,
                sourceClient = MuxTvHttpClients().source,
            ),
            revisionStore = revisionStore,
        )
    }

    private fun request(refreshRunToken: String? = null): RemoteSourceRefreshRequest =
        RemoteSourceRefreshRequest(
            sourceId = SOURCE_ID,
            sourceName = SOURCE_NAME,
            accessCredentialId = CREDENTIAL_ID,
            refreshRunToken = refreshRunToken,
        )

    private data class Fixture(
        val refresher: RemoteSourceRefresher,
        val revisionStore: RefreshOwnershipRevisionStore,
    )

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000076",
        )
        const val SOURCE_ID = "source-refresh-ownership"
        const val SOURCE_NAME = "Ownership playlist"
        const val RUN_TOKEN = "source-refresh-run-76"
        const val PLAYLIST = """#EXTM3U
#EXTINF:-1 tvg-id="one",One
https://example.invalid/live/one.m3u8
"""
    }
}

private class SourceOwnershipCredentialStore : CredentialStore {
    private val records = mutableMapOf<CredentialId, ByteArray>()

    override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
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

private class RefreshOwnershipRevisionStore(
    private val activationResult: SourceRevisionActivationResult,
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

private fun sequenceClock(vararg values: Long): () -> Long {
    val queue = ArrayDeque(values.toList())
    return { queue.removeFirst() }
}

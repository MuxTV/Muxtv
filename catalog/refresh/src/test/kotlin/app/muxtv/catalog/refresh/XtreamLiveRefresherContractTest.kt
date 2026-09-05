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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test

class XtreamLiveRefresherContractTest {
    @Test
    fun `authenticated live sync publishes only opaque non-secret playback references`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(AUTH_ACTIVE).build())
            server.enqueue(MockResponse.Builder().body(LIVE_BASIC).build())
            val fixture = fixture(server, insecureHttpApproved = true)

            val result = fixture.refresher.refresh(request(refreshRunToken = RUN_TOKEN))

            assertThat(result).isInstanceOf(XtreamLiveRefreshResult.Refreshed::class.java)
            val refreshed = result as XtreamLiveRefreshResult.Refreshed
            assertThat(refreshed.entryCount).isEqualTo(1)
            assertThat(fixture.revisionStore.guardedCredentialRefs).containsExactly(
                SourceAccessReference.xtream(CREDENTIAL_ID).value,
            )
            assertThat(fixture.revisionStore.guardedRunTokens).containsExactly(RUN_TOKEN)

            val staged = fixture.revisionStore.batches.single().single()
            assertThat(staged.providerKey).isEqualTo("provider:707")
            assertThat(staged.locator).isEqualTo("muxtv-provider://xtream/live/707")
            assertThat(staged.locator).doesNotContain(USERNAME)
            assertThat(staged.locator).doesNotContain(PASSWORD)
            assertThat(staged.locator).doesNotContain(server.hostName)
            assertThat(staged.tvgId).isEqualTo("epg-707")
            assertThat(staged.groupTitle).isNull()
            assertThat(staged.catchupMode).isEqualTo("xtream")
            assertThat(staged.catchupSource).isNull()
            assertThat(staged.catchupDays).isEqualTo(7)
            assertThat(staged.catchupCorrection).isNull()

            assertThat(server.requestCount).isEqualTo(2)
            val authRequest = server.takeRequest()
            val liveRequest = server.takeRequest()
            assertThat(authRequest.url.encodedPath).isEqualTo("/player_api.php")
            assertThat(authRequest.url.queryParameter("username")).isEqualTo(USERNAME)
            assertThat(authRequest.url.queryParameter("password")).isEqualTo(PASSWORD)
            assertThat(authRequest.url.queryParameter("action")).isNull()
            assertThat(liveRequest.url.encodedPath).isEqualTo("/player_api.php")
            assertThat(liveRequest.url.queryParameter("username")).isEqualTo(USERNAME)
            assertThat(liveRequest.url.queryParameter("password")).isEqualTo(PASSWORD)
            assertThat(liveRequest.url.queryParameter("action")).isEqualTo("get_live_streams")
        }
    }

    @Test
    fun `authentication rejection is typed and never starts a revision or live request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(AUTH_REJECTED).build())
            val fixture = fixture(server, insecureHttpApproved = true)

            val result = fixture.refresher.refresh(request())

            assertThat(result).isEqualTo(XtreamLiveRefreshResult.AuthenticationRejected)
            assertThat(fixture.revisionStore.begunRevisions).isEmpty()
            assertThat(fixture.revisionStore.activations).isEmpty()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `plain http is blocked before network when approval is absent`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, insecureHttpApproved = false)

            val result = fixture.refresher.refresh(request())

            assertThat(result).isEqualTo(XtreamLiveRefreshResult.InsecureTransportApprovalRequired)
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(fixture.revisionStore.begunRevisions).isEmpty()
        }
    }

    @Test
    fun `invalid live payload discards in-progress revision and preserves previous-good publication`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(AUTH_ACTIVE).build())
            server.enqueue(MockResponse.Builder().body("{\"not\":\"a-live-array\"}").build())
            val fixture = fixture(server, insecureHttpApproved = true)

            val result = fixture.refresher.refresh(request(refreshRunToken = RUN_TOKEN))

            assertThat(result).isInstanceOf(XtreamLiveRefreshResult.ProtocolFailure::class.java)
            assertThat(fixture.revisionStore.begunRevisions).containsExactly(1L)
            assertThat(fixture.revisionStore.activations).isEmpty()
            assertThat(fixture.revisionStore.discardedRevisions).containsExactly(1L)
        }
    }

    @Test
    fun `cancellation remains terminal and is never translated to a provider failure`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(AUTH_ACTIVE).build())
            server.enqueue(MockResponse.Builder().body(LIVE_BASIC).build())
            val fixture = fixture(
                server = server,
                insecureHttpApproved = true,
                cancelOnStage = true,
            )

            val cancelled = try {
                fixture.refresher.refresh(request(refreshRunToken = RUN_TOKEN))
                null
            } catch (error: CancellationException) {
                error
            }

            assertThat(cancelled).isNotNull()
            assertThat(fixture.revisionStore.activations).isEmpty()
            assertThat(fixture.revisionStore.discardedRevisions).containsExactly(1L)
        }
    }

    @Test
    fun `encrypted access contract round-trips while diagnostics redact every secret field`() = runTest {
        val store = InMemoryXtreamCredentialStore()
        val manager = XtreamSourceAccessManager(store)
        val access = XtreamSourceAccess(
            baseUrl = "https://provider.example/root/",
            username = USERNAME,
            password = PASSWORD,
            insecureHttpApproved = false,
        )

        assertThat(manager.save(CREDENTIAL_ID, access)).isEqualTo(CredentialWriteResult.Stored)
        val read = manager.read(CREDENTIAL_ID)

        assertThat(read).isInstanceOf(XtreamSourceAccessReadResult.Found::class.java)
        val decoded = (read as XtreamSourceAccessReadResult.Found).access
        assertThat(decoded.baseUrl).isEqualTo(access.baseUrl)
        assertThat(decoded.username).isEqualTo(USERNAME)
        assertThat(decoded.password).isEqualTo(PASSWORD)
        assertThat(decoded.insecureHttpApproved).isFalse()

        val diagnostics = access.toString() + decoded.toString() + request(refreshRunToken = RUN_TOKEN)
        assertThat(diagnostics).doesNotContain(access.baseUrl)
        assertThat(diagnostics).doesNotContain(USERNAME)
        assertThat(diagnostics).doesNotContain(PASSWORD)
        assertThat(diagnostics).doesNotContain(SOURCE_ID)
        assertThat(diagnostics).doesNotContain(SOURCE_NAME)
        assertThat(diagnostics).doesNotContain(CREDENTIAL_ID.value)
        assertThat(diagnostics).doesNotContain(RUN_TOKEN)
    }

    private suspend fun fixture(
        server: MockWebServer,
        insecureHttpApproved: Boolean,
        cancelOnStage: Boolean = false,
    ): Fixture {
        val credentialStore = InMemoryXtreamCredentialStore()
        val accessManager = XtreamSourceAccessManager(credentialStore)
        assertThat(
            accessManager.save(
                CREDENTIAL_ID,
                XtreamSourceAccess(
                    baseUrl = server.url("/").toString(),
                    username = USERNAME,
                    password = PASSWORD,
                    insecureHttpApproved = insecureHttpApproved,
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)

        val revisionStore = XtreamRefreshRevisionStore(cancelOnStage = cancelOnStage)
        val importer = CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = revisionStore,
            nowEpochMillis = sequenceClock(10, 20, 30, 40),
        )
        return Fixture(
            refresher = XtreamLiveRefresher(
                accessManager = accessManager,
                importer = importer,
                sourceClient = MuxTvHttpClients().source,
                parser = StreamingXtreamParser(),
            ),
            revisionStore = revisionStore,
        )
    }

    private fun request(refreshRunToken: String? = null): XtreamLiveRefreshRequest =
        XtreamLiveRefreshRequest(
            sourceId = SOURCE_ID,
            sourceName = SOURCE_NAME,
            accessCredentialId = CREDENTIAL_ID,
            refreshRunToken = refreshRunToken,
        )

    private data class Fixture(
        val refresher: XtreamLiveRefresher,
        val revisionStore: XtreamRefreshRevisionStore,
    )

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000224",
        )
        const val SOURCE_ID = "source-xtream-live"
        const val SOURCE_NAME = "Synthetic Xtream Live"
        const val RUN_TOKEN = "xtream-refresh-run-224"
        const val USERNAME = "TEST_USER_224"
        const val PASSWORD = "TEST_PASS_224"
        const val AUTH_ACTIVE =
            "{\"user_info\":{\"auth\":1,\"status\":\"Active\",\"allowed_output_formats\":[\"ts\",\"m3u8\"]}}"
        const val AUTH_REJECTED =
            "{\"user_info\":{\"auth\":0,\"status\":\"Disabled\",\"allowed_output_formats\":[\"ts\"]}}"
        const val LIVE_BASIC =
            "[{\"num\":1,\"name\":\"Synthetic Live\",\"stream_type\":\"live\",\"stream_id\":707," +
                "\"stream_icon\":\"https://images.example/logo.png\",\"epg_channel_id\":\"epg-707\"," +
                "\"category_id\":\"10\",\"tv_archive\":1,\"tv_archive_duration\":7}]"
    }
}

private class InMemoryXtreamCredentialStore : CredentialStore {
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

private class XtreamRefreshRevisionStore(
    private val cancelOnStage: Boolean,
) : SourceRevisionStore {
    val begunRevisions = mutableListOf<Long>()
    val batches = mutableListOf<List<StagedCatalogEntry>>()
    val activations = mutableListOf<Long>()
    val guardedCredentialRefs = mutableListOf<String>()
    val guardedRunTokens = mutableListOf<String>()
    val discardedRevisions = mutableListOf<Long>()

    override suspend fun upsertSource(source: SourceDefinition) = Unit

    override suspend fun nextRevisionNumber(sourceId: String): Long = 1

    override suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) {
        begunRevisions += revisionNumber
    }

    override suspend fun stageBatch(
        sourceId: String,
        revisionNumber: Long,
        entries: List<StagedCatalogEntry>,
    ) {
        if (cancelOnStage) throw CancellationException("synthetic cancellation")
        batches += entries.toList()
    }

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        activations += revisionNumber
        return SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = 0,
            entryCount = statistics.parsedEntries,
        )
    }

    override suspend fun activateIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        guardedCredentialRefs += expectedCredentialRef
        activations += revisionNumber
        return SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = 0,
            entryCount = statistics.parsedEntries,
        )
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
        activations += revisionNumber
        return SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = 0,
            entryCount = statistics.parsedEntries,
        )
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

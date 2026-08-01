package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.EpgRevisionImporter
import app.muxtv.catalog.ingest.StreamingXmltvParser
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.EpgChannelEntity
import app.muxtv.database.EpgProgrammeEntity
import app.muxtv.database.EpgRevisionActivationResult
import app.muxtv.database.EpgRevisionStatistics
import app.muxtv.database.EpgRevisionStore
import app.muxtv.database.EpgSourceDefinition
import app.muxtv.network.MuxTvHttpClients
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.Test

class RemoteEpgRefresherTest {
    @Test
    fun `plain XMLTV refresh sends conditional headers and activates revision`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .headers(
                        headersOf(
                            "Content-Type", "application/xml",
                            "ETag", "W/\"new-etag\"",
                            "Last-Modified", "Thu, 22 Oct 2015 07:28:00 GMT",
                        ),
                    )
                    .body(XMLTV)
                    .build(),
            )
            val fixture = fixture(server)

            val result = fixture.refresher.refresh(
                request(
                    validators = EpgHttpValidators(
                        etag = "W/\"old-etag\"",
                        lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                    ),
                ),
            )

            val refreshed = result as RemoteEpgRefreshResult.Refreshed
            assertThat(refreshed.payloadFormat).isEqualTo(EpgPayloadFormat.Plain)
            assertThat(refreshed.channelCount).isEqualTo(1)
            assertThat(refreshed.programmeCount).isEqualTo(1)
            assertThat(refreshed.validators).isEqualTo(
                EpgHttpValidators(
                    etag = "W/\"new-etag\"",
                    lastModified = "Thu, 22 Oct 2015 07:28:00 GMT",
                ),
            )
            assertThat(fixture.revisionStore.activeRevision).isEqualTo(1)
            assertThat(fixture.revisionStore.stagedProgrammes).hasSize(1)

            val recorded = server.takeRequest()
            assertThat(recorded.headers["If-None-Match"]).isEqualTo("W/\"old-etag\"")
            assertThat(recorded.headers["If-Modified-Since"])
                .isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT")
            assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer private-token")
        }
    }

    @Test
    fun `not modified preserves absent validators and creates no staging revision`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(304)
                    .headers(headersOf("ETag", "W/\"new-etag\""))
                    .build(),
            )
            val fixture = fixture(server)
            val previous = EpgHttpValidators(
                etag = "W/\"old-etag\"",
                lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
            )

            val result = fixture.refresher.refresh(request(validators = previous))

            assertThat(result).isEqualTo(
                RemoteEpgRefreshResult.NotModified(
                    EpgHttpValidators(
                        etag = "W/\"new-etag\"",
                        lastModified = previous.lastModified,
                    ),
                ),
            )
            assertThat(fixture.revisionStore.begunRevisions).isEmpty()
            assertThat(fixture.revisionStore.stagedProgrammes).isEmpty()
        }
    }

    @Test
    fun `not modified without request validators is an HTTP failure`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(304).build())
            val fixture = fixture(server)

            val result = fixture.refresher.refresh(request())

            assertThat(result).isEqualTo(RemoteEpgRefreshResult.HttpFailure(304))
            assertThat(fixture.revisionStore.begunRevisions).isEmpty()
        }
    }

    @Test
    fun `gzip XMLTV is decoded before streaming import`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .headers(headersOf("Content-Type", "application/gzip"))
                    .body(Buffer().write(gzip(XMLTV.toByteArray())))
                    .build(),
            )
            val fixture = fixture(server)

            val result = fixture.refresher.refresh(request())

            val refreshed = result as RemoteEpgRefreshResult.Refreshed
            assertThat(refreshed.payloadFormat).isEqualTo(EpgPayloadFormat.Gzip)
            assertThat(refreshed.programmeCount).isEqualTo(1)
            assertThat(fixture.revisionStore.activeRevision).isEqualTo(1)
        }
    }

    @Test
    fun `decoded payload overflow remains typed and discards staging revision`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .headers(headersOf("Content-Type", "application/gzip"))
                    .body(Buffer().write(gzip(XMLTV.toByteArray())))
                    .build(),
            )
            val fixture = fixture(server)

            val result = fixture.refresher.refresh(
                request(
                    decodeLimits = EpgPayloadDecodeLimits(maxDecodedBytes = 64),
                ),
            )

            assertThat(result).isEqualTo(
                RemoteEpgRefreshResult.PayloadRejected(
                    EpgPayloadRejectionReason.DecodedSizeExceeded,
                ),
            )
            assertThat(fixture.revisionStore.activeRevision).isEqualTo(0)
            assertThat(fixture.revisionStore.discardedRevisions).containsExactly(1L)
        }
    }

    @Test
    fun `unapproved HTTP EPG source is rejected before network access`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, insecureHttpApproved = false)

            val result = fixture.refresher.refresh(request())

            assertThat(result).isEqualTo(RemoteEpgRefreshResult.InsecureTransportApprovalRequired)
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(fixture.revisionStore.begunRevisions).isEmpty()
        }
    }

    private suspend fun fixture(
        server: MockWebServer,
        insecureHttpApproved: Boolean = true,
    ): Fixture {
        val credentialStore = EpgTestCredentialStore()
        val accessManager = RemoteSourceAccessManager(credentialStore)
        assertThat(
            accessManager.save(
                CREDENTIAL_ID,
                RemoteSourceAccess(
                    url = server.url("/guide.xml").toString(),
                    insecureHttpApproved = insecureHttpApproved,
                    userAgent = "MuxTV EPG Test",
                    sensitiveHeaders = mapOf(
                        "Authorization" to "Bearer private-token",
                    ),
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)
        val revisionStore = RecordingEpgRevisionStore()
        val importer = EpgRevisionImporter(
            parser = StreamingXmltvParser(),
            revisionStore = revisionStore,
            nowEpochMillis = sequenceClock(10, 20, 30, 40),
        )
        return Fixture(
            refresher = RemoteEpgRefresher(
                accessManager = accessManager,
                importer = importer,
                sourceClient = MuxTvHttpClients().source,
            ),
            revisionStore = revisionStore,
        )
    }

    private fun request(
        validators: EpgHttpValidators = EpgHttpValidators(),
        decodeLimits: EpgPayloadDecodeLimits = EpgPayloadDecodeLimits(),
    ): RemoteEpgRefreshRequest = RemoteEpgRefreshRequest(
        sourceId = "epg-source-one",
        sourceName = "Synthetic guide",
        providerSourceId = "playlist-source-one",
        accessCredentialId = CREDENTIAL_ID,
        defaultZoneId = null,
        validators = validators,
        decodeLimits = decodeLimits,
    )

    private data class Fixture(
        val refresher: RemoteEpgRefresher,
        val revisionStore: RecordingEpgRevisionStore,
    )

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000069",
        )

        val XMLTV: String = """
            <tv>
              <channel id="channel-one"><display-name>Channel One</display-name></channel>
              <programme channel="channel-one" start="20260801010000 +0000" stop="20260801013000 +0000">
                <title>Programme One</title>
              </programme>
            </tv>
        """.trimIndent()
    }
}

private class EpgTestCredentialStore : CredentialStore {
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
        if (records.remove(id) != null) {
            CredentialRemoveResult.Removed
        } else {
            CredentialRemoveResult.NotFound
        }

    override suspend fun reset(): CredentialResetResult {
        records.clear()
        return CredentialResetResult.Reset
    }
}

private class RecordingEpgRevisionStore : EpgRevisionStore {
    val begunRevisions = mutableListOf<Long>()
    val discardedRevisions = mutableListOf<Long>()
    val stagedChannels = mutableListOf<EpgChannelEntity>()
    val stagedProgrammes = mutableListOf<EpgProgrammeEntity>()
    var activeRevision: Long = 0
        private set
    private var nextRevision = 1L

    override suspend fun upsertSource(source: EpgSourceDefinition) = Unit

    override suspend fun beginRevision(sourceId: String, startedAtEpochMillis: Long): Long {
        val revisionNumber = nextRevision
        nextRevision += 1
        begunRevisions += revisionNumber
        return revisionNumber
    }

    override suspend fun stageBatch(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    ) {
        stagedChannels += channels
        stagedProgrammes += programmes
    }

    override suspend fun activateRevision(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult = activateRecordedRevision(revisionNumber)

    override suspend fun activateRevisionIfAccessMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedAccessRef: String,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult = activateRecordedRevision(revisionNumber)

    override suspend fun activateRevisionIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedAccessRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult = activateRecordedRevision(revisionNumber)

    private fun activateRecordedRevision(revisionNumber: Long): EpgRevisionActivationResult {
        val programmeCount = stagedProgrammes.count { it.revisionNumber == revisionNumber }
        if (programmeCount == 0) return EpgRevisionActivationResult.EmptyRevisionRejected
        val previous = activeRevision
        activeRevision = revisionNumber
        return EpgRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = previous,
            programmeCount = programmeCount,
        )
    }

    override suspend fun discardRevision(sourceId: String, revisionNumber: Long) {
        discardedRevisions += revisionNumber
        stagedChannels.removeAll { it.revisionNumber == revisionNumber }
        stagedProgrammes.removeAll { it.revisionNumber == revisionNumber }
    }
}

private fun sequenceClock(vararg values: Long): () -> Long {
    val queue = ArrayDeque(values.toList())
    return { queue.removeFirst() }
}

private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(bytes) }
    output.toByteArray()
}

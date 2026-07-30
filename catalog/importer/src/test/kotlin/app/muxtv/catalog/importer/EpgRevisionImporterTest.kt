package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.StreamingXmltvParser
import app.muxtv.database.EpgChannelEntity
import app.muxtv.database.EpgProgrammeEntity
import app.muxtv.database.EpgRevisionActivationResult
import app.muxtv.database.EpgRevisionStatistics
import app.muxtv.database.EpgRevisionStore
import app.muxtv.database.EpgSourceDefinition
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class EpgRevisionImporterTest {
    @Test
    fun `stages resolved records in bounded batches and activates after parse`() = runBlocking {
        val store = RecordingEpgRevisionStore()
        val importer = EpgRevisionImporter(
            parser = StreamingXmltvParser(),
            revisionStore = store,
            batchSize = 2,
            nowEpochMillis = sequenceClock(10, 20),
        )
        val xml = """
            <tv>
              <channel id="one"><display-name lang="en">One</display-name></channel>
              <channel id="two"><display-name lang="en">Two</display-name></channel>
              <programme channel="one" start="20260730120000 +0000" stop="20260730123000 +0000">
                <title lang="en">First title</title>
              </programme>
              <programme channel="two" start="20260730130000 +0000">
                <title lang="en">Second title</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = importer.import(request(), ByteArrayInputStream(xml.toByteArray()))

        assertThat(result).isEqualTo(
            EpgImportResult.Imported(
                revisionNumber = 1,
                previousRevisionNumber = 0,
                channelCount = 2,
                programmeCount = 2,
                skippedProgrammeCount = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )
        assertThat(store.sources).containsExactly(
            EpgSourceDefinition(
                id = "epg-1",
                name = "Synthetic EPG",
                providerSourceId = "playlist-1",
                accessRef = "opaque-ref",
                defaultZoneId = null,
            ),
        )
        assertThat(store.begunRevisions).containsExactly(Triple("epg-1", 1L, 10L))
        assertThat(store.batches.size).isAtLeast(2)
        assertThat(store.stagedChannels.map(EpgChannelEntity::externalId))
            .containsExactly("one", "two").inOrder()
        assertThat(store.stagedProgrammes.map(EpgProgrammeEntity::sequenceNumber))
            .containsExactly(1L, 2L).inOrder()
        assertThat(store.stagedProgrammes.map(EpgProgrammeEntity::primaryTitle))
            .containsExactly("First title", "Second title").inOrder()
        assertThat(store.activationStatistics.single()).isEqualTo(
            EpgRevisionStatistics(
                acceptedChannels = 2,
                acceptedProgrammes = 2,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )
        assertThat(store.discardedRevisions).isEmpty()
    }

    @Test
    fun `does not interpret offsetless programme as UTC`() = runBlocking {
        val store = RecordingEpgRevisionStore()
        val importer = EpgRevisionImporter(
            parser = StreamingXmltvParser(),
            revisionStore = store,
            nowEpochMillis = sequenceClock(10, 20),
        )
        val xml = """
            <tv>
              <channel id="one"><display-name>One</display-name></channel>
              <programme channel="one" start="20260730120000">
                <title>Unresolved title</title>
              </programme>
            </tv>
        """.trimIndent()

        val result = importer.import(request(), ByteArrayInputStream(xml.toByteArray()))

        assertThat(result).isEqualTo(EpgImportResult.EmptyRevisionRejected)
        assertThat(store.stagedProgrammes).isEmpty()
        assertThat(store.activationStatistics.single().unresolvedTimeCount).isEqualTo(1)
        assertThat(store.activationStatistics.single().skippedProgrammes).isEqualTo(1)
        assertThat(store.discardedRevisions).containsExactly("epg-1" to 1L)
    }

    @Test
    fun `parser failure discards staging revision and returns redacted failure`() = runBlocking {
        val store = RecordingEpgRevisionStore()
        val importer = EpgRevisionImporter(
            parser = StreamingXmltvParser(),
            revisionStore = store,
            nowEpochMillis = sequenceClock(10),
        )
        val privateXml = "<tv><programme channel=\"private-channel\" start=\"20260730120000 +0000\"><title>Private title"

        val result = importer.import(request(), ByteArrayInputStream(privateXml.toByteArray()))

        assertThat(result).isEqualTo(EpgImportResult.Failed(EpgImportFailureReason.ParserFailure))
        assertThat(result.toString()).doesNotContain("private-channel")
        assertThat(result.toString()).doesNotContain("Private title")
        assertThat(store.discardedRevisions).containsExactly("epg-1" to 1L)
        assertThat(store.activationStatistics).isEmpty()
    }

    @Test
    fun `cancellation discards staging and propagates cancellation`() {
        val store = RecordingEpgRevisionStore(cancelOnFirstBatch = true)
        val importer = EpgRevisionImporter(
            parser = StreamingXmltvParser(),
            revisionStore = store,
            batchSize = 1,
            nowEpochMillis = sequenceClock(10),
        )
        val xml = "<tv><channel id=\"one\"/></tv>"

        val failure = assertThrows(CancellationException::class.java) {
            runBlocking {
                importer.import(request(), ByteArrayInputStream(xml.toByteArray()))
            }
        }

        assertThat(failure.message).contains("expected cancellation")
        assertThat(store.discardedRevisions).containsExactly("epg-1" to 1L)
        assertThat(store.activationStatistics).isEmpty()
    }

    private fun request(): EpgImportRequest = EpgImportRequest(
        sourceId = "epg-1",
        sourceName = "Synthetic EPG",
        providerSourceId = "playlist-1",
        accessRef = "opaque-ref",
        defaultZoneId = null,
    )
}

private class RecordingEpgRevisionStore(
    private val cancelOnFirstBatch: Boolean = false,
) : EpgRevisionStore {
    val sources = mutableListOf<EpgSourceDefinition>()
    val begunRevisions = mutableListOf<Triple<String, Long, Long>>()
    val batches = mutableListOf<Pair<List<EpgChannelEntity>, List<EpgProgrammeEntity>>>()
    val discardedRevisions = mutableListOf<Pair<String, Long>>()
    val activationStatistics = mutableListOf<EpgRevisionStatistics>()
    val stagedChannels: List<EpgChannelEntity> get() = batches.flatMap(Pair<List<EpgChannelEntity>, List<EpgProgrammeEntity>>::first)
    val stagedProgrammes: List<EpgProgrammeEntity> get() = batches.flatMap(Pair<List<EpgChannelEntity>, List<EpgProgrammeEntity>>::second)

    override suspend fun upsertSource(source: EpgSourceDefinition) {
        sources += source
    }

    override suspend fun nextRevisionNumber(sourceId: String): Long = 1

    override suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) {
        begunRevisions += Triple(sourceId, revisionNumber, startedAtEpochMillis)
    }

    override suspend fun stageBatch(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    ) {
        if (cancelOnFirstBatch && batches.isEmpty()) {
            throw CancellationException("expected cancellation")
        }
        batches += channels to programmes
    }

    override suspend fun activateRevision(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult {
        activationStatistics += statistics
        val programmeCount = stagedProgrammes.size
        return if (programmeCount == 0) {
            EpgRevisionActivationResult.EmptyRevisionRejected
        } else {
            EpgRevisionActivationResult.Activated(revisionNumber, 0, programmeCount)
        }
    }

    override suspend fun discardRevision(sourceId: String, revisionNumber: Long) {
        discardedRevisions += sourceId to revisionNumber
    }
}

private fun sequenceClock(vararg values: Long): () -> Long {
    val queue = ArrayDeque(values.toList())
    return { queue.removeFirst() }
}

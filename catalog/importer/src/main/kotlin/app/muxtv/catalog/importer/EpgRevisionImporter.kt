package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.StreamingXmltvParser
import app.muxtv.catalog.ingest.XmltvChannel
import app.muxtv.catalog.ingest.XmltvParseException
import app.muxtv.catalog.ingest.XmltvParseLimits
import app.muxtv.catalog.ingest.XmltvParseSink
import app.muxtv.catalog.ingest.XmltvProgramme
import app.muxtv.catalog.ingest.XmltvTimestamp
import app.muxtv.catalog.ingest.XmltvWarning
import app.muxtv.database.EpgChannelEntity
import app.muxtv.database.EpgProgrammeEntity
import app.muxtv.database.EpgRevisionActivationResult
import app.muxtv.database.EpgRevisionStatistics
import app.muxtv.database.EpgRevisionStore
import app.muxtv.database.EpgSourceDefinition
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class EpgImportRequest(
    val sourceId: String,
    val sourceName: String,
    val providerSourceId: String?,
    val accessRef: String?,
    val defaultZoneId: String?,
    val parseLimits: XmltvParseLimits = XmltvParseLimits(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
    }

    override fun toString(): String =
        "EpgImportRequest(providerLinked=${providerSourceId != null}, " +
            "accessRefPresent=${accessRef != null}, defaultZonePresent=${defaultZoneId != null})"
}

sealed interface EpgImportResult {
    data class Imported(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val channelCount: Int,
        val programmeCount: Int,
        val skippedProgrammeCount: Int,
        val warningCount: Int,
        val unresolvedTimeCount: Int,
    ) : EpgImportResult

    data object EmptyRevisionRejected : EpgImportResult
    data class Failed(val reason: EpgImportFailureReason) : EpgImportResult
}

enum class EpgImportFailureReason {
    ParserFailure,
    StorageFailure,
}

class EpgRevisionImporter(
    private val parser: StreamingXmltvParser,
    private val revisionStore: EpgRevisionStore,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE)
    }

    suspend fun import(
        request: EpgImportRequest,
        input: InputStream,
    ): EpgImportResult {
        var revisionNumber: Long? = null
        var activated = false

        return try {
            revisionStore.upsertSource(
                EpgSourceDefinition(
                    id = request.sourceId,
                    name = request.sourceName,
                    providerSourceId = request.providerSourceId,
                    accessRef = request.accessRef,
                    defaultZoneId = request.defaultZoneId,
                ),
            )
            val revision = revisionStore.nextRevisionNumber(request.sourceId)
            revisionNumber = revision
            revisionStore.beginRevision(
                sourceId = request.sourceId,
                revisionNumber = revision,
                startedAtEpochMillis = nowEpochMillis(),
            )

            val sink = EpgStagingSink(
                sourceId = request.sourceId,
                revisionNumber = revision,
                revisionStore = revisionStore,
                batchSize = batchSize,
            )
            parser.parse(
                input = input,
                sink = sink,
                limits = request.parseLimits,
            )
            sink.flush()

            val statistics = EpgRevisionStatistics(
                acceptedChannels = sink.acceptedChannelCount,
                acceptedProgrammes = sink.acceptedProgrammeCount,
                skippedProgrammes = sink.skippedProgrammeCount,
                warningCount = sink.warningCount,
                unresolvedTimeCount = sink.unresolvedTimeCount,
            )
            when (
                val activation = revisionStore.activateRevision(
                    sourceId = request.sourceId,
                    revisionNumber = revision,
                    activatedAtEpochMillis = nowEpochMillis(),
                    statistics = statistics,
                )
            ) {
                is EpgRevisionActivationResult.Activated -> {
                    activated = true
                    EpgImportResult.Imported(
                        revisionNumber = activation.revisionNumber,
                        previousRevisionNumber = activation.previousRevisionNumber,
                        channelCount = sink.acceptedChannelCount,
                        programmeCount = activation.programmeCount,
                        skippedProgrammeCount = sink.skippedProgrammeCount,
                        warningCount = sink.warningCount,
                        unresolvedTimeCount = sink.unresolvedTimeCount,
                    )
                }

                EpgRevisionActivationResult.EmptyRevisionRejected ->
                    EpgImportResult.EmptyRevisionRejected

                EpgRevisionActivationResult.NotStaging ->
                    EpgImportResult.Failed(EpgImportFailureReason.StorageFailure)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: XmltvParseException) {
            EpgImportResult.Failed(EpgImportFailureReason.ParserFailure)
        } catch (_: Exception) {
            EpgImportResult.Failed(EpgImportFailureReason.StorageFailure)
        } finally {
            val revision = revisionNumber
            if (!activated && revision != null) {
                withContext(NonCancellable) {
                    try {
                        revisionStore.discardRevision(request.sourceId, revision)
                    } catch (_: Exception) {
                        // Cleanup is best effort; the original result or cancellation remains authoritative.
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 250
        const val MAX_BATCH_SIZE = 1_000
    }
}

private class EpgStagingSink(
    private val sourceId: String,
    private val revisionNumber: Long,
    private val revisionStore: EpgRevisionStore,
    private val batchSize: Int,
) : XmltvParseSink {
    private val channels = ArrayList<EpgChannelEntity>(batchSize)
    private val programmes = ArrayList<EpgProgrammeEntity>(batchSize)
    private var sequenceNumber = 0L

    var acceptedChannelCount: Int = 0
        private set
    var acceptedProgrammeCount: Int = 0
        private set
    var skippedProgrammeCount: Int = 0
        private set
    var warningCount: Int = 0
        private set
    var unresolvedTimeCount: Int = 0
        private set

    override suspend fun onChannel(channel: XmltvChannel) {
        channels += EpgChannelEntity(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            externalId = channel.externalId,
            primaryDisplayName = channel.displayNames.firstOrNull()?.value,
            primaryLanguage = channel.displayNames.firstOrNull()?.language,
            iconRef = channel.icons.firstOrNull()?.source,
        )
        acceptedChannelCount += 1
        flushWhenFull()
    }

    override suspend fun onProgramme(programme: XmltvProgramme) {
        val start = programme.start as? XmltvTimestamp.Resolved
        val stop = programme.stop
        val resolvedStop = stop as? XmltvTimestamp.Resolved
        if (start == null || (stop != null && resolvedStop == null)) {
            skippedProgrammeCount += 1
            unresolvedTimeCount += 1
            return
        }

        sequenceNumber += 1
        programmes += EpgProgrammeEntity(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            sequenceNumber = sequenceNumber,
            externalChannelId = programme.externalChannelId,
            startEpochMillis = start.instant.toEpochMilli(),
            stopEpochMillis = resolvedStop?.instant?.toEpochMilli(),
            primaryTitle = programme.titles.firstOrNull()?.value,
            primaryLanguage = programme.titles.firstOrNull()?.language,
            subtitle = programme.subTitles.firstOrNull()?.value,
            description = programme.descriptions.firstOrNull()?.value,
            category = programme.categories.firstOrNull()?.value,
            iconRef = programme.icons.firstOrNull()?.source,
            episodeNumber = programme.episodeNumbers.firstOrNull()?.value,
            isNew = programme.isNew,
        )
        acceptedProgrammeCount += 1
        flushWhenFull()
    }

    override suspend fun onWarning(warning: XmltvWarning) {
        warningCount += 1
    }

    suspend fun flush() {
        if (channels.isEmpty() && programmes.isEmpty()) return
        revisionStore.stageBatch(
            channels = channels.toList(),
            programmes = programmes.toList(),
        )
        channels.clear()
        programmes.clear()
    }

    private suspend fun flushWhenFull() {
        if (channels.size + programmes.size >= batchSize) flush()
    }
}

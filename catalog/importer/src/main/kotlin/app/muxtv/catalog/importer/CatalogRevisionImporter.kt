package app.muxtv.catalog.importer

import androidx.tracing.Trace
import app.muxtv.catalog.ingest.M3uEncodingException
import app.muxtv.catalog.ingest.M3uEntry
import app.muxtv.catalog.ingest.M3uLimitExceededException
import app.muxtv.catalog.ingest.M3uParseLimits
import app.muxtv.catalog.ingest.M3uParseOptions
import app.muxtv.catalog.ingest.M3uParseSink
import app.muxtv.catalog.ingest.M3uPlaylistHeader
import app.muxtv.catalog.ingest.M3uWarning
import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.SourceRevisionStore
import app.muxtv.database.StagedCatalogEntry
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

data class CatalogImportRequest(
    val sourceId: String,
    val sourceName: String,
    val credentialRef: String? = null,
    val parseLimits: M3uParseLimits = M3uParseLimits(),
    val parseOptions: M3uParseOptions = M3uParseOptions(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
    }
}

sealed interface CatalogImportResult {
    data class Imported(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val entryCount: Int,
        val skippedEntries: Int,
        val warningCount: Int,
    ) : CatalogImportResult

    data object EmptyRevisionRejected : CatalogImportResult

    data class Failed(
        val reason: CatalogImportFailureReason,
    ) : CatalogImportResult
}

enum class CatalogImportFailureReason {
    InvalidEncoding,
    ParserLimitExceeded,
    StorageFailure,
}

/**
 * Streams an M3U source into a staging revision and atomically activates it only after parsing and
 * all database batches complete successfully. The caller retains ownership of [InputStream].
 */
class CatalogRevisionImporter(
    private val parser: StreamingM3uParser,
    private val revisionStore: SourceRevisionStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun import(
        request: CatalogImportRequest,
        input: InputStream,
    ): CatalogImportResult {
        var revisionNumber: Long? = null

        return try {
            revisionStore.upsertSource(
                SourceDefinition(
                    id = request.sourceId,
                    name = request.sourceName,
                    credentialRef = request.credentialRef,
                ),
            )
            val revision = revisionStore.nextRevisionNumber(request.sourceId)
            revisionNumber = revision
            revisionStore.beginRevision(
                sourceId = request.sourceId,
                revisionNumber = revision,
                startedAtEpochMillis = nowEpochMillis(),
            )

            val sink = RevisionStagingSink(
                sourceId = request.sourceId,
                revisionNumber = revision,
                revisionStore = revisionStore,
                identityFactory = CatalogEntryIdentityFactory(),
            )
            val report = traceAsyncSection(TRACE_PARSE) {
                parser.parse(
                    input = input,
                    sink = sink,
                    limits = request.parseLimits,
                    options = request.parseOptions,
                )
            }
            sink.flush()

            val activation = traceAsyncSection(TRACE_ACTIVATE) {
                revisionStore.activate(
                    sourceId = request.sourceId,
                    revisionNumber = revision,
                    activatedAtEpochMillis = nowEpochMillis(),
                    statistics = SourceRevisionStatistics(
                        parsedEntries = report.parsedEntries,
                        skippedEntries = report.skippedEntries,
                        warningCount = report.warningCount,
                    ),
                )
            }
            when (activation) {
                is SourceRevisionActivationResult.Activated -> CatalogImportResult.Imported(
                    revisionNumber = activation.revisionNumber,
                    previousRevisionNumber = activation.previousRevisionNumber,
                    entryCount = activation.entryCount,
                    skippedEntries = report.skippedEntries,
                    warningCount = report.warningCount,
                )

                SourceRevisionActivationResult.EmptyRevisionRejected -> {
                    revisionStore.discard(request.sourceId, revision)
                    CatalogImportResult.EmptyRevisionRejected
                }
            }
        } catch (error: CancellationException) {
            revisionNumber?.let { revision ->
                runCatching { revisionStore.discard(request.sourceId, revision) }
            }
            throw error
        } catch (error: M3uEncodingException) {
            discardSafely(request.sourceId, revisionNumber)
            CatalogImportResult.Failed(CatalogImportFailureReason.InvalidEncoding)
        } catch (error: M3uLimitExceededException) {
            discardSafely(request.sourceId, revisionNumber)
            CatalogImportResult.Failed(CatalogImportFailureReason.ParserLimitExceeded)
        } catch (error: Exception) {
            discardSafely(request.sourceId, revisionNumber)
            CatalogImportResult.Failed(CatalogImportFailureReason.StorageFailure)
        }
    }

    private suspend fun discardSafely(
        sourceId: String,
        revisionNumber: Long?,
    ) {
        if (revisionNumber == null) return
        try {
            revisionStore.discard(sourceId, revisionNumber)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the original typed import failure when best-effort discard also fails.
        }
    }
}

private class RevisionStagingSink(
    private val sourceId: String,
    private val revisionNumber: Long,
    private val revisionStore: SourceRevisionStore,
    private val identityFactory: CatalogEntryIdentityFactory,
) : M3uParseSink {
    private var batch = ArrayList<StagedCatalogEntry>(BATCH_SIZE)
    private var entryOrdinal = 0L

    override suspend fun onHeader(header: M3uPlaylistHeader) = Unit

    override suspend fun onWarning(warning: M3uWarning) = Unit

    override suspend fun onEntry(entry: M3uEntry) {
        entryOrdinal += 1
        batch += entry.toStagedEntry(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            ordinal = entryOrdinal,
            identityFactory = identityFactory,
        )
        if (batch.size >= BATCH_SIZE) flush()
    }

    suspend fun flush() {
        if (batch.isEmpty()) return

        val pending = batch
        batch = ArrayList(BATCH_SIZE)
        revisionStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            entries = pending,
        )
    }

    private companion object {
        const val BATCH_SIZE = 250
    }
}

private fun M3uEntry.toStagedEntry(
    sourceId: String,
    revisionNumber: Long,
    ordinal: Long,
    identityFactory: CatalogEntryIdentityFactory,
): StagedCatalogEntry {
    val identity = identityFactory.create(
        entry = this,
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        ordinal = ordinal,
    )

    return StagedCatalogEntry(
        providerChannelId = identity.providerChannelId,
        providerKey = identity.providerKey,
        rawName = displayName,
        canonicalChannelId = identity.canonicalChannelId,
        canonicalDisplayName = tvgName?.takeIf(String::isNotBlank) ?: displayName,
        streamVariantId = identity.streamVariantId,
        locator = locator,
        tvgId = tvgId,
        tvgName = tvgName,
        logoUrl = tvgLogo,
        groupTitle = groupTitle,
        channelNumber = channelNumber,
        catchupMode = catchupMode,
        catchupSource = catchupSource,
        catchupDays = catchupDays,
        catchupCorrection = catchupCorrection,
        userAgent = userAgent,
        referrer = referrer,
    )
}

private suspend inline fun <T> traceAsyncSection(
    sectionName: String,
    block: () -> T,
): T {
    if (!Trace.isEnabled()) return block()

    val cookie = TRACE_COOKIE.incrementAndGet()
    Trace.beginAsyncSection(sectionName, cookie)
    return try {
        block()
    } finally {
        Trace.endAsyncSection(sectionName, cookie)
    }
}

private const val TRACE_PARSE = "MuxTV.catalog.parse"
private const val TRACE_ACTIVATE = "MuxTV.catalog.activate"
private val TRACE_COOKIE = AtomicInteger()

package app.muxtv.catalog.importer

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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
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
            )
            val report = parser.parse(
                input = input,
                sink = sink,
                limits = request.parseLimits,
                options = request.parseOptions,
            )
            sink.flush()

            when (
                val activation = revisionStore.activate(
                    sourceId = request.sourceId,
                    revisionNumber = revision,
                    activatedAtEpochMillis = nowEpochMillis(),
                    statistics = SourceRevisionStatistics(
                        parsedEntries = report.parsedEntries,
                        skippedEntries = report.skippedEntries,
                        warningCount = report.warningCount,
                    ),
                )
            ) {
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
        if (revisionNumber != null) {
            runCatching { revisionStore.discard(sourceId, revisionNumber) }
        }
    }
}

private class RevisionStagingSink(
    private val sourceId: String,
    private val revisionNumber: Long,
    private val revisionStore: SourceRevisionStore,
) : M3uParseSink {
    private val batch = ArrayList<StagedCatalogEntry>(BATCH_SIZE)
    private var entryOrdinal = 0L

    override suspend fun onHeader(header: M3uPlaylistHeader) = Unit

    override suspend fun onWarning(warning: M3uWarning) = Unit

    override suspend fun onEntry(entry: M3uEntry) {
        entryOrdinal += 1
        batch += entry.toStagedEntry(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            ordinal = entryOrdinal,
        )
        if (batch.size >= BATCH_SIZE) flush()
    }

    suspend fun flush() {
        if (batch.isEmpty()) return
        revisionStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            entries = batch.toList(),
        )
        batch.clear()
    }

    private companion object {
        const val BATCH_SIZE = 250
    }
}

private fun M3uEntry.toStagedEntry(
    sourceId: String,
    revisionNumber: Long,
    ordinal: Long,
): StagedCatalogEntry {
    val providerKey = providerKey()
    val canonicalScope = if (!tvgId.isNullOrBlank()) {
        "global|$providerKey"
    } else {
        "source|$sourceId|$providerKey"
    }
    val providerChannelId = stableId("provider|$sourceId|$revisionNumber|$ordinal")
    val canonicalChannelId = stableId("canonical|$canonicalScope")
    val streamVariantId = stableId("stream|$sourceId|$revisionNumber|$ordinal")

    return StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = providerKey,
        rawName = displayName,
        canonicalChannelId = canonicalChannelId,
        canonicalDisplayName = tvgName?.takeIf(String::isNotBlank) ?: displayName,
        streamVariantId = streamVariantId,
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

private fun M3uEntry.providerKey(): String {
    val stableTvgId = tvgId?.normalizeIdentityPart()
    if (!stableTvgId.isNullOrEmpty()) return "tvg:$stableTvgId"

    return buildString {
        append("name:")
        append((tvgName ?: displayName).normalizeIdentityPart())
        append("|group:")
        append(groupTitle.orEmpty().normalizeIdentityPart())
        append("|number:")
        append(channelNumber.orEmpty().normalizeIdentityPart())
    }
}

private fun String.normalizeIdentityPart(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")

private fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private val WHITESPACE = Regex("\\s+")

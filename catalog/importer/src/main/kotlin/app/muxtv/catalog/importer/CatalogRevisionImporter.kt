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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class CatalogImportSourceOwnership {
    UPSERT_METADATA,
    EXISTING_REMOTE_BINDING,
}

data class CatalogImportRequest(
    val sourceId: String,
    val sourceName: String,
    val credentialRef: String? = null,
    val refreshRunToken: String? = null,
    val parseLimits: M3uParseLimits = M3uParseLimits(),
    val parseOptions: M3uParseOptions = M3uParseOptions(),
    val sourceOwnership: CatalogImportSourceOwnership = CatalogImportSourceOwnership.UPSERT_METADATA,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(credentialRef == null || credentialRef.isNotBlank())
        require(refreshRunToken == null || refreshRunToken.isNotBlank())
        if (sourceOwnership == CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING) {
            require(!credentialRef.isNullOrBlank()) {
                "Existing remote catalog imports require an opaque credential binding."
            }
        }
    }

    override fun toString(): String =
        "CatalogImportRequest(credentialRefPresent=${credentialRef != null}, " +
            "refreshRunTokenPresent=${refreshRunToken != null}, sourceOwnership=$sourceOwnership)"
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
    data object Superseded : CatalogImportResult

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
 * Owns catalog revision staging and atomic activation for both the legacy M3U parser path and
 * provider-neutral streaming feeds. Callers retain ownership of any transport resources used by a
 * feed.
 */
class CatalogRevisionImporter(
    private val parser: StreamingM3uParser,
    private val revisionStore: SourceRevisionStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun import(
        request: CatalogImportRequest,
        input: InputStream,
    ): CatalogImportResult = try {
        importEntries(
            request = request.toRevisionImportRequest(),
            feed = M3uCatalogImportFeed(
                parser = parser,
                input = input,
                limits = request.parseLimits,
                options = request.parseOptions,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: M3uEncodingException) {
        CatalogImportResult.Failed(CatalogImportFailureReason.InvalidEncoding)
    } catch (_: M3uLimitExceededException) {
        CatalogImportResult.Failed(CatalogImportFailureReason.ParserLimitExceeded)
    } catch (_: Exception) {
        CatalogImportResult.Failed(CatalogImportFailureReason.StorageFailure)
    }

    suspend fun importEntries(
        request: CatalogRevisionImportRequest,
        feed: CatalogImportFeed,
    ): CatalogImportResult {
        var revisionNumber: Long? = null

        return try {
            if (request.sourceOwnership == CatalogImportSourceOwnership.UPSERT_METADATA) {
                revisionStore.upsertSource(
                    SourceDefinition(
                        id = request.sourceId,
                        name = request.sourceName,
                        credentialRef = request.credentialRef,
                    ),
                )
            }
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
                feed.streamTo(sink)
            }
            sink.flush()

            val statistics = SourceRevisionStatistics(
                parsedEntries = report.parsedEntries,
                skippedEntries = report.skippedEntries,
                warningCount = report.warningCount,
            )
            val activation = traceAsyncSection(TRACE_ACTIVATE) {
                when (request.sourceOwnership) {
                    CatalogImportSourceOwnership.UPSERT_METADATA -> revisionStore.activate(
                        sourceId = request.sourceId,
                        revisionNumber = revision,
                        activatedAtEpochMillis = nowEpochMillis(),
                        statistics = statistics,
                    )

                    CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING -> {
                        val expectedCredentialRef = requireNotNull(request.credentialRef)
                        val refreshRunToken = request.refreshRunToken
                        if (refreshRunToken == null) {
                            revisionStore.activateIfCredentialMatches(
                                sourceId = request.sourceId,
                                revisionNumber = revision,
                                expectedCredentialRef = expectedCredentialRef,
                                activatedAtEpochMillis = nowEpochMillis(),
                                statistics = statistics,
                            )
                        } else {
                            revisionStore.activateIfRefreshOwnerMatches(
                                sourceId = request.sourceId,
                                revisionNumber = revision,
                                expectedCredentialRef = expectedCredentialRef,
                                expectedRunToken = refreshRunToken,
                                activatedAtEpochMillis = nowEpochMillis(),
                                statistics = statistics,
                            )
                        }
                    }
                }
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
                    discardSafely(request.sourceId, revision)
                    CatalogImportResult.EmptyRevisionRejected
                }

                SourceRevisionActivationResult.Superseded -> {
                    // Guarded Room activation already discards staging transactionally. Calling
                    // discard again keeps alternate/test stores honest and remains idempotent.
                    discardSafely(request.sourceId, revision)
                    CatalogImportResult.Superseded
                }
            }
        } catch (cancelled: CancellationException) {
            discardAfterCancellationBestEffort(
                sourceId = request.sourceId,
                revisionNumber = revisionNumber,
            )
            throw cancelled
        } catch (failure: Exception) {
            discardSafely(request.sourceId, revisionNumber)
            throw failure
        }
    }

    private suspend fun discardAfterCancellationBestEffort(
        sourceId: String,
        revisionNumber: Long?,
    ) {
        if (revisionNumber == null) return
        withContext(NonCancellable) {
            try {
                revisionStore.discard(sourceId, revisionNumber)
            } catch (_: Exception) {
                // The original request cancellation is authoritative.
            }
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
            // Preserve the original import failure when best-effort discard also fails.
        }
    }
}

private class M3uCatalogImportFeed(
    private val parser: StreamingM3uParser,
    private val input: InputStream,
    private val limits: M3uParseLimits,
    private val options: M3uParseOptions,
) : CatalogImportFeed {
    override suspend fun streamTo(sink: CatalogImportEntrySink): CatalogImportFeedReport {
        val report = parser.parse(
            input = input,
            sink = object : M3uParseSink {
                override suspend fun onHeader(header: M3uPlaylistHeader) = Unit

                override suspend fun onWarning(warning: M3uWarning) = Unit

                override suspend fun onEntry(entry: M3uEntry) {
                    sink.onEntry(entry.toCatalogImportEntry())
                }
            },
            limits = limits,
            options = options,
        )
        return CatalogImportFeedReport(
            parsedEntries = report.parsedEntries,
            skippedEntries = report.skippedEntries,
            warningCount = report.warningCount,
        )
    }
}

private class RevisionStagingSink(
    private val sourceId: String,
    private val revisionNumber: Long,
    private val revisionStore: SourceRevisionStore,
    private val identityFactory: CatalogEntryIdentityFactory,
) : CatalogImportEntrySink {
    private val batch = ArrayList<StagedCatalogEntry>(BATCH_SIZE)
    private var entryOrdinal = 0L

    override suspend fun onEntry(entry: CatalogImportEntry) {
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

private fun CatalogImportEntry.toStagedEntry(
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
        locator = playbackReference,
        tvgId = tvgId,
        tvgName = tvgName,
        logoUrl = logoUrl,
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

private fun CatalogImportRequest.toRevisionImportRequest(): CatalogRevisionImportRequest =
    CatalogRevisionImportRequest(
        sourceId = sourceId,
        sourceName = sourceName,
        credentialRef = credentialRef,
        refreshRunToken = refreshRunToken,
        sourceOwnership = sourceOwnership,
    )

private suspend inline fun <T> traceAsyncSection(
    sectionName: String,
    block: () -> T,
): T {
    val traceCookie = try {
        if (!Trace.isEnabled()) {
            null
        } else {
            TRACE_COOKIE.incrementAndGet().also { cookie ->
                Trace.beginAsyncSection(sectionName, cookie)
            }
        }
    } catch (_: RuntimeException) {
        // Android framework stubs used by local JVM tests do not implement android.os.Trace.
        // Tracing is diagnostic-only and must never change importer behavior.
        null
    }

    return try {
        block()
    } finally {
        if (traceCookie != null) {
            try {
                Trace.endAsyncSection(sectionName, traceCookie)
            } catch (_: RuntimeException) {
                // A tracing backend failure must not replace the parser/storage result.
            }
        }
    }
}

private const val TRACE_PARSE = "MuxTV.catalog.parse"
private const val TRACE_ACTIVATE = "MuxTV.catalog.activate"
private val TRACE_COOKIE = AtomicInteger()

package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogImportEntry
import app.muxtv.catalog.importer.CatalogImportEntrySink
import app.muxtv.catalog.importer.CatalogImportFailureReason
import app.muxtv.catalog.importer.CatalogImportFeed
import app.muxtv.catalog.importer.CatalogImportFeedReport
import app.muxtv.catalog.importer.CatalogImportResult
import app.muxtv.catalog.importer.CatalogImportSourceOwnership
import app.muxtv.catalog.importer.CatalogRevisionImportRequest
import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.ingest.StreamingXtreamParser
import app.muxtv.catalog.ingest.XtreamAuthResult
import app.muxtv.catalog.ingest.XtreamFormatException
import app.muxtv.catalog.ingest.XtreamLimitExceededException
import app.muxtv.catalog.ingest.XtreamLiveEntry
import app.muxtv.catalog.ingest.XtreamLiveSink
import app.muxtv.catalog.ingest.XtreamParseLimits
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.network.RedirectRejectedException
import app.muxtv.network.RedirectRejectionReason
import app.muxtv.network.ResponseSizeKind
import app.muxtv.network.ResponseSizeLimits
import app.muxtv.network.ResponseTooLargeException
import app.muxtv.network.SourceRequestContext
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import app.muxtv.network.SourceUrlRejectionReason
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.DateTimeException
import java.time.ZoneId
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class XtreamLiveRefreshRequest(
    val sourceId: String,
    val sourceName: String,
    val accessCredentialId: CredentialId,
    val accessReference: SourceAccessReference = SourceAccessReference.xtream(accessCredentialId),
    val refreshRunToken: String? = null,
    val responseSizeLimits: ResponseSizeLimits = ResponseSizeLimits(),
    val parseLimits: XtreamParseLimits = XtreamParseLimits(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(accessReference.kind == SourceAccessKind.XTREAM) {
            "Xtream refresh requires an Xtream source access reference."
        }
        require(accessReference.credentialId == accessCredentialId) {
            "Xtream source access reference must match its credential id."
        }
        require(refreshRunToken == null || refreshRunToken.isNotBlank())
    }

    override fun toString(): String =
        "XtreamLiveRefreshRequest(refreshRunTokenPresent=${refreshRunToken != null})"
}

sealed interface XtreamLiveRefreshResult {
    data class Refreshed(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val entryCount: Int,
        val skippedEntries: Int,
        val warningCount: Int,
    ) : XtreamLiveRefreshResult

    data object Superseded : XtreamLiveRefreshResult
    data object AccessCredentialNotFound : XtreamLiveRefreshResult

    data class AccessCredentialUnavailable(
        val reason: CredentialUnavailableReason,
    ) : XtreamLiveRefreshResult

    data object AccessCredentialCorrupted : XtreamLiveRefreshResult

    data class UrlRejected(
        val reason: SourceUrlRejectionReason,
    ) : XtreamLiveRefreshResult

    data object LocalNetworkAccessRequired : XtreamLiveRefreshResult
    data object InsecureTransportApprovalRequired : XtreamLiveRefreshResult
    data object AuthenticationRejected : XtreamLiveRefreshResult

    data class HttpFailure(
        val statusCode: Int,
    ) : XtreamLiveRefreshResult

    data class ResponseTooLarge(
        val kind: ResponseSizeKind,
        val limitBytes: Long,
    ) : XtreamLiveRefreshResult

    data class RedirectRejected(
        val reason: RedirectRejectionReason,
    ) : XtreamLiveRefreshResult

    data class NetworkFailure(
        val reason: RemoteSourceNetworkFailureReason,
    ) : XtreamLiveRefreshResult

    data object ProtocolFailure : XtreamLiveRefreshResult
    data object EmptyRevisionRejected : XtreamLiveRefreshResult

    data class ImportFailed(
        val reason: CatalogImportFailureReason,
    ) : XtreamLiveRefreshResult
}

class XtreamLiveRefresher(
    private val accessManager: XtreamSourceAccessManager,
    private val importer: CatalogRevisionImporter,
    private val sourceClient: OkHttpClient,
    private val parser: StreamingXtreamParser,
    private val localNetworkAccessRequired: (String) -> Boolean = { false },
) {
    suspend fun refresh(request: XtreamLiveRefreshRequest): XtreamLiveRefreshResult {
        val access = when (val accessResult = accessManager.read(request.accessCredentialId)) {
            is XtreamSourceAccessReadResult.Found -> accessResult.access
            XtreamSourceAccessReadResult.NotFound -> return XtreamLiveRefreshResult.AccessCredentialNotFound
            XtreamSourceAccessReadResult.Corrupted -> return XtreamLiveRefreshResult.AccessCredentialCorrupted
            is XtreamSourceAccessReadResult.Unavailable ->
                return XtreamLiveRefreshResult.AccessCredentialUnavailable(accessResult.reason)
        }

        val urlDecision = SourceUrlPolicy.evaluate(access.baseUrl)
        val normalizedBaseUrl = when (urlDecision) {
            is SourceUrlDecision.Allowed -> urlDecision.normalizedUrl
            is SourceUrlDecision.RequiresInsecureTransportApproval -> urlDecision.normalizedUrl
            is SourceUrlDecision.Rejected -> return XtreamLiveRefreshResult.UrlRejected(urlDecision.reason)
        }

        if (localNetworkAccessRequired(normalizedBaseUrl)) {
            return XtreamLiveRefreshResult.LocalNetworkAccessRequired
        }
        if (
            urlDecision is SourceUrlDecision.RequiresInsecureTransportApproval &&
            !access.insecureHttpApproved
        ) {
            return XtreamLiveRefreshResult.InsecureTransportApprovalRequired
        }

        val requestContext = SourceRequestContext(
            insecureHttpApproved = access.insecureHttpApproved,
            responseSizeLimits = request.responseSizeLimits,
        )
        val endpoint = normalizedBaseUrl.toXtreamEndpoint()

        return try {
            val archiveCapabilityEnabled = when (
                val auth = executeAuth(
                    endpoint = endpoint,
                    access = access,
                    requestContext = requestContext,
                    limits = request.parseLimits,
                )
            ) {
                XtreamAuthResult.Rejected -> return XtreamLiveRefreshResult.AuthenticationRejected
                is XtreamAuthResult.Authenticated -> updateArchiveTimeZone(
                    credentialId = request.accessCredentialId,
                    access = access,
                    serverTimeZoneId = auth.serverTimeZoneId,
                )
            }

            executeLiveImport(
                endpoint = endpoint,
                access = access,
                requestContext = requestContext,
                request = request,
                archiveCapabilityEnabled = archiveCapabilityEnabled,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: XtreamFormatException) {
            XtreamLiveRefreshResult.ProtocolFailure
        } catch (_: XtreamLimitExceededException) {
            XtreamLiveRefreshResult.ProtocolFailure
        } catch (error: ResponseTooLargeException) {
            XtreamLiveRefreshResult.ResponseTooLarge(
                kind = error.kind,
                limitBytes = error.limitBytes,
            )
        } catch (error: RedirectRejectedException) {
            XtreamLiveRefreshResult.RedirectRejected(error.reason)
        } catch (error: XtreamHttpStatusException) {
            XtreamLiveRefreshResult.HttpFailure(error.statusCode)
        } catch (_: SocketTimeoutException) {
            XtreamLiveRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Timeout)
        } catch (_: UnknownHostException) {
            XtreamLiveRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Dns)
        } catch (_: SSLException) {
            XtreamLiveRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Tls)
        } catch (_: IOException) {
            XtreamLiveRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Io)
        } catch (_: Exception) {
            XtreamLiveRefreshResult.ImportFailed(CatalogImportFailureReason.StorageFailure)
        }
    }

    private suspend fun updateArchiveTimeZone(
        credentialId: CredentialId,
        access: XtreamSourceAccess,
        serverTimeZoneId: String?,
    ): Boolean {
        val normalizedTimeZoneId = normalizeArchiveTimeZoneId(serverTimeZoneId)
        if (normalizedTimeZoneId == null) {
            if (access.archiveTimeZoneId != null) {
                accessManager.save(
                    credentialId,
                    access.withArchiveTimeZoneId(null),
                )
            }
            return false
        }

        return accessManager.save(
            credentialId,
            access.withArchiveTimeZoneId(normalizedTimeZoneId),
        ) == CredentialWriteResult.Stored
    }

    private fun normalizeArchiveTimeZoneId(value: String?): String? {
        val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (candidate.length > XtreamSourceAccess.MAX_ARCHIVE_TIME_ZONE_ID_CHARACTERS) return null
        if (candidate.any(Char::isISOControl)) return null

        return try {
            ZoneId.of(candidate).id
        } catch (_: DateTimeException) {
            null
        }
    }

    private suspend fun executeAuth(
        endpoint: HttpUrl,
        access: XtreamSourceAccess,
        requestContext: SourceRequestContext,
        limits: XtreamParseLimits,
    ): XtreamAuthResult {
        val authUrl = endpoint.newBuilder()
            .addQueryParameter("username", access.username)
            .addQueryParameter("password", access.password)
            .build()
        val networkRequest = jsonRequest(authUrl, requestContext)

        return sourceClient.newCall(networkRequest).awaitSourceResponse().use { response ->
            if (!response.isSuccessful) {
                throw XtreamHttpStatusException(response.code)
            }
            parser.parseAuth(response.body.byteStream(), limits)
        }
    }

    private suspend fun executeLiveImport(
        endpoint: HttpUrl,
        access: XtreamSourceAccess,
        requestContext: SourceRequestContext,
        request: XtreamLiveRefreshRequest,
        archiveCapabilityEnabled: Boolean,
    ): XtreamLiveRefreshResult {
        val liveUrl = endpoint.newBuilder()
            .addQueryParameter("username", access.username)
            .addQueryParameter("password", access.password)
            .addQueryParameter("action", "get_live_streams")
            .build()
        val networkRequest = jsonRequest(liveUrl, requestContext)

        return sourceClient.newCall(networkRequest).awaitSourceResponse().use { response ->
            if (!response.isSuccessful) {
                return XtreamLiveRefreshResult.HttpFailure(response.code)
            }

            val durableRefresh = request.refreshRunToken != null
            when (
                val imported = importer.importEntries(
                    request = CatalogRevisionImportRequest(
                        sourceId = request.sourceId,
                        sourceName = request.sourceName,
                        credentialRef = request.accessReference.value,
                        refreshRunToken = request.refreshRunToken,
                        sourceOwnership = if (durableRefresh) {
                            CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING
                        } else {
                            CatalogImportSourceOwnership.UPSERT_METADATA
                        },
                    ),
                    feed = XtreamLiveCatalogFeed(
                        parser = parser,
                        input = response.body.byteStream(),
                        limits = request.parseLimits,
                        archiveCapabilityEnabled = archiveCapabilityEnabled,
                    ),
                )
            ) {
                is CatalogImportResult.Imported -> XtreamLiveRefreshResult.Refreshed(
                    revisionNumber = imported.revisionNumber,
                    previousRevisionNumber = imported.previousRevisionNumber,
                    entryCount = imported.entryCount,
                    skippedEntries = imported.skippedEntries,
                    warningCount = imported.warningCount,
                )

                CatalogImportResult.Superseded -> XtreamLiveRefreshResult.Superseded
                CatalogImportResult.EmptyRevisionRejected -> XtreamLiveRefreshResult.EmptyRevisionRejected
                is CatalogImportResult.Failed -> XtreamLiveRefreshResult.ImportFailed(imported.reason)
            }
        }
    }

    private fun jsonRequest(
        url: HttpUrl,
        requestContext: SourceRequestContext,
    ): Request = Request.Builder()
        .url(url)
        .get()
        .header("Accept", JSON_ACCEPT)
        .header("User-Agent", DEFAULT_USER_AGENT)
        .tag(SourceRequestContext::class, requestContext)
        .build()

    private companion object {
        const val DEFAULT_USER_AGENT = "MuxTV/0.0.1"
        const val JSON_ACCEPT = "application/json, text/json, */*"
    }
}

private class XtreamLiveCatalogFeed(
    private val parser: StreamingXtreamParser,
    private val input: java.io.InputStream,
    private val limits: XtreamParseLimits,
    private val archiveCapabilityEnabled: Boolean,
) : CatalogImportFeed {
    override suspend fun streamTo(sink: CatalogImportEntrySink): CatalogImportFeedReport {
        val report = parser.parseLive(
            input = input,
            sink = object : XtreamLiveSink {
                override suspend fun onEntry(entry: XtreamLiveEntry) {
                    sink.onEntry(entry.toCatalogImportEntry(archiveCapabilityEnabled))
                }
            },
            limits = limits,
        )
        return CatalogImportFeedReport(
            parsedEntries = report.parsedEntries,
            skippedEntries = report.skippedEntries,
            warningCount = report.warningCount,
        )
    }
}

private fun XtreamLiveEntry.toCatalogImportEntry(
    archiveCapabilityEnabled: Boolean,
): CatalogImportEntry {
    val archiveEnabled = archiveCapabilityEnabled && archiveAvailable == true
    return CatalogImportEntry(
        providerStableId = streamId.toString(),
        displayName = name,
        playbackReference = "muxtv-provider://xtream/live/$streamId",
        tvgId = epgChannelId,
        tvgName = null,
        logoUrl = null,
        groupTitle = null,
        channelNumber = channelNumber?.toString(),
        catchupMode = if (archiveEnabled) XTREAM_CATCHUP_MODE else null,
        catchupSource = null,
        catchupDays = if (archiveEnabled) archiveDurationDays else null,
        catchupCorrection = null,
        userAgent = null,
        referrer = null,
    )
}

private fun String.toXtreamEndpoint(): HttpUrl = toHttpUrl()
    .newBuilder()
    .query(null)
    .addPathSegment("player_api.php")
    .build()

private class XtreamHttpStatusException(
    val statusCode: Int,
) : IOException("Xtream endpoint returned HTTP $statusCode.")

private const val XTREAM_CATCHUP_MODE = "xtream"

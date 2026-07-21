package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogImportRequest
import app.muxtv.catalog.importer.CatalogImportResult
import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.network.RedirectRejectedException
import app.muxtv.network.ResponseSizeLimits
import app.muxtv.network.ResponseTooLargeException
import app.muxtv.network.SourceRequestContext
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches an untrusted remote playlist through the source HTTP policy and streams the response
 * directly into immutable catalog staging. Neither the response nor the parsed catalog is buffered
 * as a complete in-memory collection.
 */
class RemoteCatalogSourceRefresher(
    private val sourceClient: OkHttpClient,
    private val importer: CatalogRevisionImporter,
) {
    suspend fun refresh(request: RemoteCatalogRefreshRequest): RemoteCatalogRefreshResult =
        withContext(Dispatchers.IO) {
            val normalizedUrl = when (val decision = SourceUrlPolicy.evaluate(request.url)) {
                is SourceUrlDecision.Allowed -> decision.normalizedUrl
                is SourceUrlDecision.RequiresInsecureTransportApproval -> {
                    if (!request.insecureHttpApproved) {
                        return@withContext RemoteCatalogRefreshResult.Rejected(
                            RemoteCatalogRefreshRejection.InsecureTransportApprovalRequired,
                        )
                    }
                    decision.normalizedUrl
                }
                is SourceUrlDecision.Rejected -> {
                    return@withContext RemoteCatalogRefreshResult.Rejected(
                        RemoteCatalogRefreshRejection.InvalidSourceUrl,
                    )
                }
            }

            val httpRequest = Request.Builder()
                .url(normalizedUrl)
                .get()
                .header("Accept", M3U_ACCEPT)
                .header("User-Agent", request.userAgent)
                .tag(
                    SourceRequestContext::class,
                    SourceRequestContext(
                        insecureHttpApproved = request.insecureHttpApproved,
                        responseSizeLimits = request.responseSizeLimits,
                    ),
                )
                .apply {
                    request.referrer?.takeIf(String::isNotBlank)?.let {
                        header("Referer", it)
                    }
                }
                .build()

            try {
                sourceClient.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext RemoteCatalogRefreshResult.HttpFailure(
                            statusCode = response.code,
                        )
                    }

                    val body = response.body
                    val importResult = body.byteStream().use { stream ->
                        importer.import(
                            request = CatalogImportRequest(
                                sourceId = request.sourceId,
                                sourceName = request.sourceName,
                                credentialRef = request.credentialRef,
                            ),
                            input = stream,
                        )
                    }
                    RemoteCatalogRefreshResult.Completed(importResult)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ResponseTooLargeException) {
                RemoteCatalogRefreshResult.NetworkFailure(
                    RemoteCatalogRefreshFailure.ResponseTooLarge,
                )
            } catch (error: RedirectRejectedException) {
                RemoteCatalogRefreshResult.NetworkFailure(
                    RemoteCatalogRefreshFailure.RedirectRejected,
                )
            } catch (error: IOException) {
                RemoteCatalogRefreshResult.NetworkFailure(
                    RemoteCatalogRefreshFailure.TransportFailure,
                )
            }
        }

    private companion object {
        const val M3U_ACCEPT =
            "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*"
    }
}

data class RemoteCatalogRefreshRequest(
    val sourceId: String,
    val sourceName: String,
    val url: String,
    val credentialRef: String? = null,
    val insecureHttpApproved: Boolean = false,
    val userAgent: String = "MuxTV/0.0.1",
    val referrer: String? = null,
    val responseSizeLimits: ResponseSizeLimits = ResponseSizeLimits(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(url.isNotBlank())
        require(userAgent.isNotBlank())
    }

    override fun toString(): String =
        "RemoteCatalogRefreshRequest(sourceId=$sourceId, sourceName=$sourceName, url=<redacted>, " +
            "credentialRef=${credentialRef?.let { "<present>" }}, insecureHttpApproved=$insecureHttpApproved)"
}

sealed interface RemoteCatalogRefreshResult {
    data class Completed(
        val importResult: CatalogImportResult,
    ) : RemoteCatalogRefreshResult

    data class Rejected(
        val reason: RemoteCatalogRefreshRejection,
    ) : RemoteCatalogRefreshResult

    data class HttpFailure(
        val statusCode: Int,
    ) : RemoteCatalogRefreshResult

    data class NetworkFailure(
        val reason: RemoteCatalogRefreshFailure,
    ) : RemoteCatalogRefreshResult
}

enum class RemoteCatalogRefreshRejection {
    InvalidSourceUrl,
    InsecureTransportApprovalRequired,
}

enum class RemoteCatalogRefreshFailure {
    ResponseTooLarge,
    RedirectRejected,
    TransportFailure,
}

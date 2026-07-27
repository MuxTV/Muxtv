package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogImportFailureReason
import app.muxtv.catalog.importer.CatalogImportRequest
import app.muxtv.catalog.importer.CatalogImportResult
import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.ingest.M3uParseLimits
import app.muxtv.catalog.ingest.M3uParseOptions
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.network.MuxTvHttpClients
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
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class RemoteSourceRefreshRequest(
    val sourceId: String,
    val sourceName: String,
    val accessCredentialId: CredentialId,
    val responseSizeLimits: ResponseSizeLimits = ResponseSizeLimits(),
    val parseLimits: M3uParseLimits = M3uParseLimits(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
    }
}

sealed interface RemoteSourceRefreshResult {
    data class Refreshed(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val entryCount: Int,
        val skippedEntries: Int,
        val warningCount: Int,
    ) : RemoteSourceRefreshResult

    data object AccessCredentialNotFound : RemoteSourceRefreshResult

    data class AccessCredentialUnavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteSourceRefreshResult

    data object AccessCredentialCorrupted : RemoteSourceRefreshResult

    data class UrlRejected(
        val reason: SourceUrlRejectionReason,
    ) : RemoteSourceRefreshResult

    data object InsecureTransportApprovalRequired : RemoteSourceRefreshResult

    data class HttpFailure(
        val statusCode: Int,
    ) : RemoteSourceRefreshResult

    data class ResponseTooLarge(
        val kind: ResponseSizeKind,
        val limitBytes: Long,
    ) : RemoteSourceRefreshResult

    data class RedirectRejected(
        val reason: RedirectRejectionReason,
    ) : RemoteSourceRefreshResult

    data class NetworkFailure(
        val reason: RemoteSourceNetworkFailureReason,
    ) : RemoteSourceRefreshResult

    data object EmptyRevisionRejected : RemoteSourceRefreshResult

    data class ImportFailed(
        val reason: CatalogImportFailureReason,
    ) : RemoteSourceRefreshResult
}

enum class RemoteSourceNetworkFailureReason {
    Timeout,
    Dns,
    Tls,
    Io,
}

class RemoteSourceRefresher(
    private val accessManager: RemoteSourceAccessManager,
    private val importer: CatalogRevisionImporter,
    private val sourceClient: OkHttpClient,
) {
    suspend fun refresh(request: RemoteSourceRefreshRequest): RemoteSourceRefreshResult {
        val access = when (val accessResult = accessManager.read(request.accessCredentialId)) {
            is RemoteSourceAccessReadResult.Found -> accessResult.access
            RemoteSourceAccessReadResult.NotFound ->
                return RemoteSourceRefreshResult.AccessCredentialNotFound

            RemoteSourceAccessReadResult.Corrupted ->
                return RemoteSourceRefreshResult.AccessCredentialCorrupted

            is RemoteSourceAccessReadResult.Unavailable ->
                return RemoteSourceRefreshResult.AccessCredentialUnavailable(accessResult.reason)
        }

        val normalizedUrl = when (val decision = SourceUrlPolicy.evaluate(access.url)) {
            is SourceUrlDecision.Allowed -> decision.normalizedUrl
            is SourceUrlDecision.RequiresInsecureTransportApproval -> {
                if (!access.insecureHttpApproved) {
                    return RemoteSourceRefreshResult.InsecureTransportApprovalRequired
                }
                decision.normalizedUrl
            }

            is SourceUrlDecision.Rejected ->
                return RemoteSourceRefreshResult.UrlRejected(decision.reason)
        }

        val networkRequest = Request.Builder()
            .url(normalizedUrl)
            .get()
            .header("Accept", M3U_ACCEPT)
            .header("User-Agent", access.userAgent ?: DEFAULT_USER_AGENT)
            .tag(
                SourceRequestContext::class,
                SourceRequestContext(
                    insecureHttpApproved = access.insecureHttpApproved,
                    responseSizeLimits = request.responseSizeLimits,
                ),
            )
            .apply {
                access.referrer?.let { header("Referer", it) }
                access.sensitiveHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()

        return try {
            sourceClient.newCall(networkRequest).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    return RemoteSourceRefreshResult.HttpFailure(response.code)
                }

                val body = response.body
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                when (
                    val imported = importer.import(
                        request = CatalogImportRequest(
                            sourceId = request.sourceId,
                            sourceName = request.sourceName,
                            credentialRef = request.accessCredentialId.value,
                            parseLimits = request.parseLimits,
                            parseOptions = M3uParseOptions(charset = charset),
                        ),
                        input = body.byteStream(),
                    )
                ) {
                    is CatalogImportResult.Imported -> RemoteSourceRefreshResult.Refreshed(
                        revisionNumber = imported.revisionNumber,
                        previousRevisionNumber = imported.previousRevisionNumber,
                        entryCount = imported.entryCount,
                        skippedEntries = imported.skippedEntries,
                        warningCount = imported.warningCount,
                    )

                    CatalogImportResult.EmptyRevisionRejected ->
                        RemoteSourceRefreshResult.EmptyRevisionRejected

                    is CatalogImportResult.Failed ->
                        RemoteSourceRefreshResult.ImportFailed(imported.reason)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseTooLargeException) {
            RemoteSourceRefreshResult.ResponseTooLarge(
                kind = error.kind,
                limitBytes = error.limitBytes,
            )
        } catch (error: RedirectRejectedException) {
            RemoteSourceRefreshResult.RedirectRejected(error.reason)
        } catch (_: SocketTimeoutException) {
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Timeout)
        } catch (_: UnknownHostException) {
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Dns)
        } catch (_: SSLException) {
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Tls)
        } catch (_: IOException) {
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Io)
        }
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "MuxTV/0.0.1"
        const val M3U_ACCEPT =
            "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, text/plain, */*"
    }
}

object RemoteSourceRefreshFactory {
    fun create(
        credentialStore: CredentialStore,
        importer: CatalogRevisionImporter,
    ): RemoteSourceRefresher {
        val accessManager = RemoteSourceAccessManager(credentialStore)
        return RemoteSourceRefresher(
            accessManager = accessManager,
            importer = importer,
            sourceClient = MuxTvHttpClients().source,
        )
    }

    fun createAccessManager(credentialStore: CredentialStore): RemoteSourceAccessManager =
        RemoteSourceAccessManager(credentialStore)
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(
                call: Call,
                exception: IOException,
            ) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }

            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        },
    )
}

package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.EpgImportRequest
import app.muxtv.catalog.importer.EpgImportResult
import app.muxtv.catalog.importer.EpgImportSourceOwnership
import app.muxtv.catalog.importer.EpgRevisionImporter
import app.muxtv.network.MuxTvHttpClients
import app.muxtv.network.RedirectRejectedException
import app.muxtv.network.ResponseTooLargeException
import app.muxtv.network.SourceRequestContext
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class RemoteEpgRefresher(
    private val accessManager: RemoteSourceAccessManager,
    private val importer: EpgRevisionImporter,
    private val sourceClient: OkHttpClient,
    private val payloadDecoder: EpgPayloadDecoder = EpgPayloadDecoder(),
) {
    suspend fun refresh(request: RemoteEpgRefreshRequest): RemoteEpgRefreshResult {
        val access = when (val accessResult = accessManager.read(request.accessCredentialId)) {
            is RemoteSourceAccessReadResult.Found -> accessResult.access
            RemoteSourceAccessReadResult.NotFound ->
                return RemoteEpgRefreshResult.AccessCredentialNotFound

            RemoteSourceAccessReadResult.Corrupted ->
                return RemoteEpgRefreshResult.AccessCredentialCorrupted

            is RemoteSourceAccessReadResult.Unavailable ->
                return RemoteEpgRefreshResult.AccessCredentialUnavailable(accessResult.reason)
        }

        val normalizedUrl = when (val decision = SourceUrlPolicy.evaluate(access.url)) {
            is SourceUrlDecision.Allowed -> decision.normalizedUrl
            is SourceUrlDecision.RequiresInsecureTransportApproval -> {
                if (!access.insecureHttpApproved) {
                    return RemoteEpgRefreshResult.InsecureTransportApprovalRequired
                }
                decision.normalizedUrl
            }

            is SourceUrlDecision.Rejected ->
                return RemoteEpgRefreshResult.UrlRejected(decision.reason)
        }

        val networkRequest = Request.Builder()
            .url(normalizedUrl)
            .get()
            .header("Accept", XMLTV_ACCEPT)
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
                request.validators.etag?.let { header("If-None-Match", it) }
                request.validators.lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()

        return try {
            sourceClient.newCall(networkRequest).awaitSourceResponse().use { response ->
                if (response.code == HTTP_NOT_MODIFIED) {
                    if (request.validators.isEmpty) {
                        return RemoteEpgRefreshResult.HttpFailure(HTTP_NOT_MODIFIED)
                    }
                    return RemoteEpgRefreshResult.NotModified(
                        validators = response.notModifiedValidators(request.validators),
                    )
                }
                if (!response.isSuccessful) {
                    return RemoteEpgRefreshResult.HttpFailure(response.code)
                }

                val responseValidators = response.replacementValidators()
                when (
                    val decoded = payloadDecoder.decode(
                        input = response.body.byteStream(),
                        hints = EpgPayloadHints(
                            contentEncoding = response.header("Content-Encoding"),
                            contentType = response.header("Content-Type"),
                        ),
                        limits = request.decodeLimits,
                    ) { decodedInput ->
                        importer.import(
                            request = EpgImportRequest(
                                sourceId = request.sourceId,
                                sourceName = request.sourceName,
                                providerSourceId = request.providerSourceId,
                                accessRef = request.accessCredentialId.value,
                                defaultZoneId = request.defaultZoneId,
                                refreshRunToken = request.refreshRunToken,
                                parseLimits = request.parseLimits,
                                sourceOwnership = EpgImportSourceOwnership.EXISTING_REMOTE_BINDING,
                            ),
                            input = decodedInput,
                        )
                    }
                ) {
                    is EpgPayloadDecodeResult.Rejected ->
                        RemoteEpgRefreshResult.PayloadRejected(decoded.reason)

                    is EpgPayloadDecodeResult.Decoded -> when (val imported = decoded.value) {
                        is EpgImportResult.Imported -> RemoteEpgRefreshResult.Refreshed(
                            revisionNumber = imported.revisionNumber,
                            previousRevisionNumber = imported.previousRevisionNumber,
                            channelCount = imported.channelCount,
                            programmeCount = imported.programmeCount,
                            skippedProgrammeCount = imported.skippedProgrammeCount,
                            warningCount = imported.warningCount,
                            unresolvedTimeCount = imported.unresolvedTimeCount,
                            payloadFormat = decoded.format,
                            validators = responseValidators,
                        )

                        EpgImportResult.EmptyRevisionRejected ->
                            RemoteEpgRefreshResult.EmptyRevisionRejected

                        EpgImportResult.Superseded ->
                            RemoteEpgRefreshResult.Superseded

                        is EpgImportResult.Failed ->
                            RemoteEpgRefreshResult.ImportFailed(imported.reason)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseTooLargeException) {
            RemoteEpgRefreshResult.ResponseTooLarge(
                kind = error.kind,
                limitBytes = error.limitBytes,
            )
        } catch (error: RedirectRejectedException) {
            RemoteEpgRefreshResult.RedirectRejected(error.reason)
        } catch (_: SocketTimeoutException) {
            RemoteEpgRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Timeout)
        } catch (_: UnknownHostException) {
            RemoteEpgRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Dns)
        } catch (_: SSLException) {
            RemoteEpgRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Tls)
        } catch (_: IOException) {
            RemoteEpgRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Io)
        }
    }

    private fun Response.replacementValidators(): EpgHttpValidators =
        EpgHttpValidators.fromResponse(
            etag = header("ETag"),
            lastModified = header("Last-Modified"),
        )

    private fun Response.notModifiedValidators(previous: EpgHttpValidators): EpgHttpValidators {
        val response = replacementValidators()
        return EpgHttpValidators(
            etag = response.etag ?: previous.etag,
            lastModified = response.lastModified ?: previous.lastModified,
        )
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
        const val DEFAULT_USER_AGENT = "MuxTV/0.0.1"
        const val XMLTV_ACCEPT =
            "application/xml, text/xml, application/gzip, application/zip, text/plain, */*"
    }
}

object RemoteEpgRefreshFactory {
    fun create(
        accessManager: RemoteSourceAccessManager,
        importer: EpgRevisionImporter,
        clients: MuxTvHttpClients,
    ): RemoteEpgRefresher = RemoteEpgRefresher(
        accessManager = accessManager,
        importer = importer,
        sourceClient = clients.source,
    )
}

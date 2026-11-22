package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialUnavailableReason
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface XtreamSourceActivator {
    suspend fun activate(request: XtreamLiveRefreshRequest): XtreamLiveRefreshResult
}

class XtreamSourceLifecycle(
    private val accessManager: XtreamSourceAccessManager,
    private val activator: XtreamSourceActivator,
    private val activationCleanup: RemoteSourceActivationCleanup,
) {
    suspend fun activate(
        accessReference: SourceAccessReference,
        sourceName: String,
    ): RemoteSourceActivationResult {
        val sourceId = sourceId(accessReference)
        val normalizedName = sourceName.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_SOURCE_NAME_CHARACTERS) {
            return cleanupFailure(
                accessReference = accessReference,
                sourceId = sourceId,
                failure = RemoteSourceActivationFailure.InvalidSourceName,
            )
        }

        val credentialId = accessReference.credentialId
        val result = try {
            activator.activate(
                XtreamLiveRefreshRequest(
                    sourceId = sourceId,
                    sourceName = normalizedName,
                    accessCredentialId = credentialId,
                    accessReference = accessReference,
                ),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    cleanupArtifacts(accessReference = accessReference, sourceId = sourceId)
                } catch (_: Exception) {
                    // Preserve the original activation cancellation. Durable onboarding retries cleanup.
                }
            }
            throw cancelled
        } catch (_: Exception) {
            return cleanupFailure(
                accessReference = accessReference,
                sourceId = sourceId,
                failure = RemoteSourceActivationFailure.Unexpected,
            )
        }

        return when (result) {
            is XtreamLiveRefreshResult.Refreshed -> RemoteSourceActivationResult.Activated(
                sourceId = sourceId,
                revisionNumber = result.revisionNumber,
                previousRevisionNumber = result.previousRevisionNumber,
                entryCount = result.entryCount,
                skippedEntries = result.skippedEntries,
                warningCount = result.warningCount,
            )

            XtreamLiveRefreshResult.Superseded -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.Unexpected,
            )

            XtreamLiveRefreshResult.AccessCredentialNotFound -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialNotFound,
            )

            is XtreamLiveRefreshResult.AccessCredentialUnavailable -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialUnavailable(result.reason),
            )

            XtreamLiveRefreshResult.AccessCredentialCorrupted -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialCorrupted,
            )

            is XtreamLiveRefreshResult.UrlRejected -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.UrlRejected(result.reason),
            )

            XtreamLiveRefreshResult.LocalNetworkAccessRequired ->
                RemoteSourceActivationResult.LocalNetworkAccessRequired

            XtreamLiveRefreshResult.InsecureTransportApprovalRequired -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.InsecureTransportApprovalRequired,
            )

            XtreamLiveRefreshResult.AuthenticationRejected -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialCorrupted,
            )

            is XtreamLiveRefreshResult.HttpFailure -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.Http(result.statusCode),
            )

            is XtreamLiveRefreshResult.ResponseTooLarge -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.ResponseTooLarge(
                    kind = result.kind,
                    limitBytes = result.limitBytes,
                ),
            )

            is XtreamLiveRefreshResult.RedirectRejected -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.RedirectRejected(result.reason),
            )

            is XtreamLiveRefreshResult.NetworkFailure -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.Network(result.reason),
            )

            XtreamLiveRefreshResult.ProtocolFailure -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.Unexpected,
            )

            XtreamLiveRefreshResult.EmptyRevisionRejected -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.EmptyRevisionRejected,
            )

            is XtreamLiveRefreshResult.ImportFailed -> cleanupFailure(
                accessReference,
                sourceId,
                RemoteSourceActivationFailure.ImportFailed(result.reason),
            )
        }
    }

    private suspend fun cleanupFailure(
        accessReference: SourceAccessReference,
        sourceId: String,
        failure: RemoteSourceActivationFailure,
    ): RemoteSourceActivationResult.Failed {
        val cleanup = cleanupArtifacts(accessReference = accessReference, sourceId = sourceId)
        return RemoteSourceActivationResult.Failed(
            failure = failure,
            credentialCleanupFailure = cleanup.credentialFailure,
            sourceCleanupFailure = cleanup.sourceFailure,
        )
    }

    private suspend fun cleanupArtifacts(
        accessReference: SourceAccessReference,
        sourceId: String,
    ): CleanupSummary {
        val sourceCleanup = try {
            activationCleanup.removeInactiveSource(
                sourceId = sourceId,
                expectedCredentialRef = accessReference.value,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CleanupSummary(
                sourceFailure = RemoteSourceMetadataCleanupFailure.Unexpected,
                credentialFailure = null,
            )
        }

        if (sourceCleanup == RemoteSourceMetadataCleanupResult.Retained) {
            return CleanupSummary(
                sourceFailure = RemoteSourceMetadataCleanupFailure.MetadataRetained,
                credentialFailure = null,
            )
        }

        val credentialFailure = when (val removed = accessManager.remove(accessReference.credentialId)) {
            CredentialRemoveResult.Removed,
            CredentialRemoveResult.NotFound,
            -> null

            is CredentialRemoveResult.Unavailable -> removed.reason
        }
        return CleanupSummary(
            sourceFailure = null,
            credentialFailure = credentialFailure,
        )
    }

    private fun sourceId(accessReference: SourceAccessReference): String {
        val input = "$SOURCE_ID_DOMAIN${accessReference.value}".toByteArray(StandardCharsets.UTF_8)
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(input)
        } finally {
            input.fill(0)
        }
        return "source-" + digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class CleanupSummary(
        val sourceFailure: RemoteSourceMetadataCleanupFailure?,
        val credentialFailure: CredentialUnavailableReason?,
    )

    private companion object {
        const val MAX_SOURCE_NAME_CHARACTERS = 200
        const val SOURCE_ID_DOMAIN = "muxtv:xtream-source:v1:"
    }
}

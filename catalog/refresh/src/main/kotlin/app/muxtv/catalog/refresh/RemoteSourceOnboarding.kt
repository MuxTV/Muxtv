package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogImportFailureReason
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.network.RedirectRejectionReason
import app.muxtv.network.ResponseSizeKind
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import app.muxtv.network.SourceUrlRejectionReason
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

interface RemoteSourceOnboarding {
    suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult

    suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult

    suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult
}

fun interface RemoteSourceActivator {
    suspend fun activate(request: RemoteSourceRefreshRequest): RemoteSourceRefreshResult
}

fun interface RemoteSourceActivationCleanup {
    suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): RemoteSourceMetadataCleanupResult
}

enum class RemoteSourceMetadataCleanupResult {
    Removed,
    NotFound,
    Retained,
}

class DefaultRemoteSourceOnboarding(
    private val accessManager: RemoteSourceAccessManager,
    private val activator: RemoteSourceActivator,
    private val activationCleanup: RemoteSourceActivationCleanup,
) : RemoteSourceOnboarding {
    override suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult {
        val normalizedUrl = when (val decision = SourceUrlPolicy.evaluate(input.locator)) {
            is SourceUrlDecision.Allowed -> decision.normalizedUrl
            is SourceUrlDecision.RequiresInsecureTransportApproval -> {
                if (!input.insecureHttpApproved) {
                    return RemoteSourcePreparationResult.InsecureTransportApprovalRequired
                }
                decision.normalizedUrl
            }

            is SourceUrlDecision.Rejected ->
                return RemoteSourcePreparationResult.UrlRejected(decision.reason)
        }

        val access = try {
            RemoteSourceAccess(
                url = normalizedUrl,
                insecureHttpApproved = input.insecureHttpApproved,
                userAgent = input.userAgent,
                referrer = input.referrer,
                sensitiveHeaders = input.sensitiveHeaders,
            )
        } catch (_: IllegalArgumentException) {
            return RemoteSourcePreparationResult.InvalidAccess
        }

        val credentialId = CredentialId.random()
        return when (val stored = accessManager.save(credentialId, access)) {
            CredentialWriteResult.Stored -> {
                val endpoint = normalizedUrl.toHttpUrl()
                RemoteSourcePreparationResult.Prepared(
                    token = RemoteSourcePreparationToken.fromCredentialId(credentialId),
                    scheme = endpoint.scheme,
                    host = endpoint.host,
                )
            }

            is CredentialWriteResult.RejectedTooLarge ->
                RemoteSourcePreparationResult.CredentialTooLarge(stored.limitBytes)

            is CredentialWriteResult.Unavailable ->
                RemoteSourcePreparationResult.CredentialUnavailable(stored.reason)
        }
    }

    override suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult {
        val sourceId = sourceId(token)
        val normalizedName = sourceName.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_SOURCE_NAME_CHARACTERS) {
            return cleanupFailure(
                token = token,
                sourceId = sourceId,
                failure = RemoteSourceActivationFailure.InvalidSourceName,
            )
        }

        val credentialId = token.credentialId()
        val result = try {
            activator.activate(
                RemoteSourceRefreshRequest(
                    sourceId = sourceId,
                    sourceName = normalizedName,
                    accessCredentialId = credentialId,
                ),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    cleanupArtifacts(token = token, sourceId = sourceId)
                } catch (_: Exception) {
                    // Preserve the original activation cancellation. Durable onboarding retries cleanup.
                }
            }
            throw cancelled
        } catch (_: Exception) {
            return cleanupFailure(
                token = token,
                sourceId = sourceId,
                failure = RemoteSourceActivationFailure.Unexpected,
            )
        }

        return when (result) {
            is RemoteSourceRefreshResult.Refreshed -> RemoteSourceActivationResult.Activated(
                sourceId = sourceId,
                revisionNumber = result.revisionNumber,
                previousRevisionNumber = result.previousRevisionNumber,
                entryCount = result.entryCount,
                skippedEntries = result.skippedEntries,
                warningCount = result.warningCount,
            )

            RemoteSourceRefreshResult.Superseded -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.Unexpected,
            )

            RemoteSourceRefreshResult.AccessCredentialNotFound -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialNotFound,
            )

            is RemoteSourceRefreshResult.AccessCredentialUnavailable -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialUnavailable(result.reason),
            )

            RemoteSourceRefreshResult.AccessCredentialCorrupted -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.AccessCredentialCorrupted,
            )

            is RemoteSourceRefreshResult.UrlRejected -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.UrlRejected(result.reason),
            )

            RemoteSourceRefreshResult.InsecureTransportApprovalRequired -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.InsecureTransportApprovalRequired,
            )

            is RemoteSourceRefreshResult.HttpFailure -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.Http(result.statusCode),
            )

            is RemoteSourceRefreshResult.ResponseTooLarge -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.ResponseTooLarge(
                    kind = result.kind,
                    limitBytes = result.limitBytes,
                ),
            )

            is RemoteSourceRefreshResult.RedirectRejected -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.RedirectRejected(result.reason),
            )

            is RemoteSourceRefreshResult.NetworkFailure -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.Network(result.reason),
            )

            RemoteSourceRefreshResult.EmptyRevisionRejected -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.EmptyRevisionRejected,
            )

            is RemoteSourceRefreshResult.ImportFailed -> cleanupFailure(
                token,
                sourceId,
                RemoteSourceActivationFailure.ImportFailed(result.reason),
            )
        }
    }

    override suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult {
        val sourceCleanup = try {
            activationCleanup.removeInactiveSource(
                sourceId = sourceId(token),
                expectedCredentialRef = token.value,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RemoteSourceCancellationResult.SourceCleanupFailed
        }
        if (sourceCleanup == RemoteSourceMetadataCleanupResult.Retained) {
            return RemoteSourceCancellationResult.MetadataRetained
        }

        return when (val removed = accessManager.remove(token.credentialId())) {
            CredentialRemoveResult.Removed -> RemoteSourceCancellationResult.Removed
            CredentialRemoveResult.NotFound -> RemoteSourceCancellationResult.NotFound
            is CredentialRemoveResult.Unavailable ->
                RemoteSourceCancellationResult.Unavailable(removed.reason)
        }
    }

    private suspend fun cleanupFailure(
        token: RemoteSourcePreparationToken,
        sourceId: String,
        failure: RemoteSourceActivationFailure,
    ): RemoteSourceActivationResult.Failed {
        val cleanup = cleanupArtifacts(token = token, sourceId = sourceId)
        return RemoteSourceActivationResult.Failed(
            failure = failure,
            credentialCleanupFailure = cleanup.credentialFailure,
            sourceCleanupFailure = cleanup.sourceFailure,
        )
    }

    private suspend fun cleanupArtifacts(
        token: RemoteSourcePreparationToken,
        sourceId: String,
    ): CleanupSummary {
        val sourceCleanup = try {
            activationCleanup.removeInactiveSource(
                sourceId = sourceId,
                expectedCredentialRef = token.value,
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

        val credentialFailure = when (val removed = accessManager.remove(token.credentialId())) {
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

    private fun sourceId(token: RemoteSourcePreparationToken): String {
        val input = "$SOURCE_ID_DOMAIN${token.value}".toByteArray(StandardCharsets.UTF_8)
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
        const val SOURCE_ID_DOMAIN = "muxtv:remote-source:v1:"
    }
}

class RemoteSourceOnboardingInput(
    val locator: String,
    val insecureHttpApproved: Boolean = false,
    val userAgent: String? = null,
    val referrer: String? = null,
    sensitiveHeaders: Map<String, String> = emptyMap(),
) {
    val sensitiveHeaders: Map<String, String> = sensitiveHeaders.toMap()

    override fun toString(): String =
        "RemoteSourceOnboardingInput(locator=<redacted>, " +
            "insecureHttpApproved=$insecureHttpApproved, " +
            "hasUserAgent=${userAgent != null}, hasReferrer=${referrer != null}, " +
            "sensitiveHeaderNames=${sensitiveHeaders.keys.sorted()})"
}

@JvmInline
value class RemoteSourcePreparationToken private constructor(
    val value: String,
) {
    init {
        CredentialId.parse(value)
    }

    internal fun credentialId(): CredentialId = CredentialId.parse(value)

    override fun toString(): String = "<redacted>"

    companion object {
        fun parse(value: String): RemoteSourcePreparationToken =
            RemoteSourcePreparationToken(CredentialId.parse(value).value)

        internal fun fromCredentialId(id: CredentialId): RemoteSourcePreparationToken =
            RemoteSourcePreparationToken(id.value)
    }
}

sealed interface RemoteSourcePreparationResult {
    data class Prepared(
        val token: RemoteSourcePreparationToken,
        val scheme: String,
        val host: String,
    ) : RemoteSourcePreparationResult {
        init {
            require(scheme == "http" || scheme == "https")
            require(host.isNotBlank())
        }
    }

    data class UrlRejected(
        val reason: SourceUrlRejectionReason,
    ) : RemoteSourcePreparationResult

    data object InsecureTransportApprovalRequired : RemoteSourcePreparationResult
    data object InvalidAccess : RemoteSourcePreparationResult

    data class CredentialTooLarge(
        val limitBytes: Int,
    ) : RemoteSourcePreparationResult

    data class CredentialUnavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteSourcePreparationResult
}

sealed interface RemoteSourceActivationResult {
    data class Activated(
        val sourceId: String,
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val entryCount: Int,
        val skippedEntries: Int,
        val warningCount: Int,
    ) : RemoteSourceActivationResult {
        init {
            require(sourceId.isNotBlank())
            require(revisionNumber > 0)
            require(previousRevisionNumber >= 0)
            require(entryCount > 0)
            require(skippedEntries >= 0)
            require(warningCount >= 0)
        }
    }

    data class Failed(
        val failure: RemoteSourceActivationFailure,
        val credentialCleanupFailure: CredentialUnavailableReason?,
        val sourceCleanupFailure: RemoteSourceMetadataCleanupFailure?,
    ) : RemoteSourceActivationResult
}

enum class RemoteSourceMetadataCleanupFailure {
    MetadataRetained,
    Unexpected,
}

sealed interface RemoteSourceActivationFailure {
    data object InvalidSourceName : RemoteSourceActivationFailure
    data object AccessCredentialNotFound : RemoteSourceActivationFailure

    data class AccessCredentialUnavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteSourceActivationFailure

    data object AccessCredentialCorrupted : RemoteSourceActivationFailure

    data class UrlRejected(
        val reason: SourceUrlRejectionReason,
    ) : RemoteSourceActivationFailure

    data object InsecureTransportApprovalRequired : RemoteSourceActivationFailure

    data class Http(
        val statusCode: Int,
    ) : RemoteSourceActivationFailure

    data class ResponseTooLarge(
        val kind: ResponseSizeKind,
        val limitBytes: Long,
    ) : RemoteSourceActivationFailure

    data class RedirectRejected(
        val reason: RedirectRejectionReason,
    ) : RemoteSourceActivationFailure

    data class Network(
        val reason: RemoteSourceNetworkFailureReason,
    ) : RemoteSourceActivationFailure

    data object EmptyRevisionRejected : RemoteSourceActivationFailure

    data class ImportFailed(
        val reason: CatalogImportFailureReason,
    ) : RemoteSourceActivationFailure

    data object Unexpected : RemoteSourceActivationFailure
}

sealed interface RemoteSourceCancellationResult {
    data object Removed : RemoteSourceCancellationResult
    data object NotFound : RemoteSourceCancellationResult
    data object MetadataRetained : RemoteSourceCancellationResult
    data object SourceCleanupFailed : RemoteSourceCancellationResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteSourceCancellationResult
}

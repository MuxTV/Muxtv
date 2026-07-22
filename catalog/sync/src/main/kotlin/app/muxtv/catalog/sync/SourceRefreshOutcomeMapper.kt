package app.muxtv.catalog.sync

import app.muxtv.catalog.importer.CatalogImportFailureReason
import app.muxtv.catalog.refresh.RemoteSourceNetworkFailureReason
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.database.SourceRefreshRunState

internal data class SourceRefreshDecision(
    val state: SourceRefreshRunState,
    val resultFamily: String,
    val resultCode: String?,
    val revisionNumber: Long? = null,
    val parsedEntries: Int? = null,
    val skippedEntries: Int = 0,
    val warningCount: Int = 0,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
)

internal object SourceRefreshOutcomeMapper {
    fun map(result: RemoteSourceRefreshResult): SourceRefreshDecision = when (result) {
        is RemoteSourceRefreshResult.Refreshed -> SourceRefreshDecision(
            state = SourceRefreshRunState.SUCCEEDED,
            resultFamily = FAMILY_SUCCESS,
            resultCode = null,
            revisionNumber = result.revisionNumber,
            parsedEntries = result.entryCount,
            skippedEntries = result.skippedEntries,
            warningCount = result.warningCount,
        )

        RemoteSourceRefreshResult.AccessCredentialNotFound -> needsAuth("NOT_FOUND")
        is RemoteSourceRefreshResult.AccessCredentialUnavailable ->
            needsAuth("UNAVAILABLE_${result.reason.stableCode()}")
        RemoteSourceRefreshResult.AccessCredentialCorrupted -> needsAuth("CORRUPTED")

        is RemoteSourceRefreshResult.UrlRejected -> failure(
            family = FAMILY_URL,
            code = result.reason.stableCode(),
        )

        RemoteSourceRefreshResult.InsecureTransportApprovalRequired -> failure(
            family = FAMILY_TRANSPORT,
            code = "INSECURE_APPROVAL_REQUIRED",
        )

        is RemoteSourceRefreshResult.HttpFailure -> mapHttpFailure(result.statusCode)

        is RemoteSourceRefreshResult.ResponseTooLarge -> failure(
            family = FAMILY_SIZE,
            code = result.kind.stableCode(),
        )

        is RemoteSourceRefreshResult.RedirectRejected -> failure(
            family = FAMILY_REDIRECT,
            code = result.reason.stableCode(),
        )

        is RemoteSourceRefreshResult.NetworkFailure -> SourceRefreshDecision(
            state = SourceRefreshRunState.FAILED,
            resultFamily = FAMILY_NETWORK,
            resultCode = result.reason.stableCode(),
            retryable = result.reason in RETRYABLE_NETWORK_REASONS,
        )

        RemoteSourceRefreshResult.EmptyRevisionRejected -> failure(
            family = FAMILY_CONTENT,
            code = "EMPTY_REVISION",
        )

        is RemoteSourceRefreshResult.ImportFailed -> SourceRefreshDecision(
            state = SourceRefreshRunState.FAILED,
            resultFamily = FAMILY_IMPORT,
            resultCode = result.reason.stableCode(),
            retryable = result.reason == CatalogImportFailureReason.StorageFailure,
        )
    }

    fun missingCredentialReference(): SourceRefreshDecision = needsAuth("MISSING_REFERENCE")

    fun invalidCredentialReference(): SourceRefreshDecision = needsAuth("INVALID_REFERENCE")

    fun internalFailure(): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.FAILED,
        resultFamily = FAMILY_INTERNAL,
        resultCode = "UNEXPECTED",
        retryable = true,
    )

    private fun mapHttpFailure(statusCode: Int): SourceRefreshDecision = when {
        statusCode == 401 || statusCode == 403 -> SourceRefreshDecision(
            state = SourceRefreshRunState.NEEDS_AUTH,
            resultFamily = FAMILY_HTTP,
            resultCode = statusCode.toString(),
            httpStatus = statusCode,
        )

        statusCode in RETRYABLE_HTTP_CODES || statusCode in 500..599 -> SourceRefreshDecision(
            state = SourceRefreshRunState.FAILED,
            resultFamily = FAMILY_HTTP,
            resultCode = statusCode.toString(),
            httpStatus = statusCode,
            retryable = true,
        )

        else -> SourceRefreshDecision(
            state = SourceRefreshRunState.FAILED,
            resultFamily = FAMILY_HTTP,
            resultCode = statusCode.toString(),
            httpStatus = statusCode,
        )
    }

    private fun needsAuth(code: String): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.NEEDS_AUTH,
        resultFamily = FAMILY_CREDENTIAL,
        resultCode = code,
    )

    private fun failure(
        family: String,
        code: String,
    ): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.FAILED,
        resultFamily = family,
        resultCode = code,
    )

    private fun Enum<*>.stableCode(): String = name
        .replace(CAMEL_BOUNDARY, "$1_$2")
        .uppercase()

    private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429)
    private val RETRYABLE_NETWORK_REASONS = setOf(
        RemoteSourceNetworkFailureReason.Timeout,
        RemoteSourceNetworkFailureReason.Dns,
        RemoteSourceNetworkFailureReason.Io,
    )
    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

    private const val FAMILY_SUCCESS = "SUCCESS"
    private const val FAMILY_CREDENTIAL = "CREDENTIAL"
    private const val FAMILY_URL = "URL"
    private const val FAMILY_TRANSPORT = "TRANSPORT"
    private const val FAMILY_HTTP = "HTTP"
    private const val FAMILY_SIZE = "SIZE"
    private const val FAMILY_REDIRECT = "REDIRECT"
    private const val FAMILY_NETWORK = "NETWORK"
    private const val FAMILY_CONTENT = "CONTENT"
    private const val FAMILY_IMPORT = "IMPORT"
    private const val FAMILY_INTERNAL = "INTERNAL"
}

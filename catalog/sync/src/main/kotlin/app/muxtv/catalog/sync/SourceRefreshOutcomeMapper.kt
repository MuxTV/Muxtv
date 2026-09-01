package app.muxtv.catalog.sync

import app.muxtv.catalog.importer.CatalogImportFailureReason
import app.muxtv.catalog.refresh.RemoteSourceNetworkFailureReason
import app.muxtv.catalog.refresh.RemoteSourceRefreshResult
import app.muxtv.catalog.refresh.XtreamLiveRefreshResult
import app.muxtv.database.SourceRefreshCompletion
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
    val workSucceeded: Boolean = false,
)

internal object SourceRefreshOutcomeMapper {
    fun map(result: RemoteSourceRefreshResult): SourceRefreshDecision = when (result) {
        is RemoteSourceRefreshResult.Refreshed -> refreshed(
            revisionNumber = result.revisionNumber,
            entryCount = result.entryCount,
            skippedEntries = result.skippedEntries,
            warningCount = result.warningCount,
        )

        RemoteSourceRefreshResult.Superseded -> superseded()
        RemoteSourceRefreshResult.AccessCredentialNotFound -> needsAuth("NOT_FOUND")
        is RemoteSourceRefreshResult.AccessCredentialUnavailable ->
            needsAuth("UNAVAILABLE_${result.reason.stableCode()}")
        RemoteSourceRefreshResult.AccessCredentialCorrupted -> needsAuth("CORRUPTED")
        is RemoteSourceRefreshResult.UrlRejected -> failure(
            family = FAMILY_URL,
            code = result.reason.stableCode(),
        )
        RemoteSourceRefreshResult.LocalNetworkAccessRequired -> localNetworkPermissionRequired()
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
        is RemoteSourceRefreshResult.NetworkFailure -> networkFailure(result.reason)
        RemoteSourceRefreshResult.EmptyRevisionRejected -> failure(
            family = FAMILY_CONTENT,
            code = "EMPTY_REVISION",
        )
        is RemoteSourceRefreshResult.ImportFailed -> importFailure(result.reason)
    }

    fun map(result: XtreamLiveRefreshResult): SourceRefreshDecision = when (result) {
        is XtreamLiveRefreshResult.Refreshed -> refreshed(
            revisionNumber = result.revisionNumber,
            entryCount = result.entryCount,
            skippedEntries = result.skippedEntries,
            warningCount = result.warningCount,
        )

        XtreamLiveRefreshResult.Superseded -> superseded()
        XtreamLiveRefreshResult.AccessCredentialNotFound -> needsAuth("NOT_FOUND")
        is XtreamLiveRefreshResult.AccessCredentialUnavailable ->
            needsAuth("UNAVAILABLE_${result.reason.stableCode()}")
        XtreamLiveRefreshResult.AccessCredentialCorrupted -> needsAuth("CORRUPTED")
        is XtreamLiveRefreshResult.UrlRejected -> failure(
            family = FAMILY_URL,
            code = result.reason.stableCode(),
        )
        XtreamLiveRefreshResult.LocalNetworkAccessRequired -> localNetworkPermissionRequired()
        XtreamLiveRefreshResult.InsecureTransportApprovalRequired -> failure(
            family = FAMILY_TRANSPORT,
            code = "INSECURE_APPROVAL_REQUIRED",
        )
        XtreamLiveRefreshResult.AuthenticationRejected -> needsAuth("AUTHENTICATION_REJECTED")
        is XtreamLiveRefreshResult.HttpFailure -> mapHttpFailure(result.statusCode)
        is XtreamLiveRefreshResult.ResponseTooLarge -> failure(
            family = FAMILY_SIZE,
            code = result.kind.stableCode(),
        )
        is XtreamLiveRefreshResult.RedirectRejected -> failure(
            family = FAMILY_REDIRECT,
            code = result.reason.stableCode(),
        )
        is XtreamLiveRefreshResult.NetworkFailure -> networkFailure(result.reason)
        XtreamLiveRefreshResult.ProtocolFailure -> failure(
            family = FAMILY_CONTENT,
            code = "PROTOCOL_FAILURE",
        )
        XtreamLiveRefreshResult.EmptyRevisionRejected -> failure(
            family = FAMILY_CONTENT,
            code = "EMPTY_REVISION",
        )
        is XtreamLiveRefreshResult.ImportFailed -> importFailure(result.reason)
    }

    fun missingCredentialReference(): SourceRefreshDecision = needsAuth("MISSING_REFERENCE")

    fun invalidCredentialReference(): SourceRefreshDecision = needsAuth("INVALID_REFERENCE")

    fun runtimeTimeout(): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.FAILED,
        resultFamily = FAMILY_WORK,
        resultCode = "TIMEOUT",
        retryable = true,
    )

    fun internalFailure(): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.FAILED,
        resultFamily = FAMILY_INTERNAL,
        resultCode = "UNEXPECTED",
        retryable = true,
    )

    private fun refreshed(
        revisionNumber: Long,
        entryCount: Int,
        skippedEntries: Int,
        warningCount: Int,
    ): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.SUCCEEDED,
        resultFamily = FAMILY_SUCCESS,
        resultCode = null,
        revisionNumber = revisionNumber,
        parsedEntries = entryCount,
        skippedEntries = skippedEntries,
        warningCount = warningCount,
        workSucceeded = true,
    )

    private fun superseded(): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.CANCELLED,
        resultFamily = SourceRefreshCompletion.RESULT_FAMILY,
        resultCode = SourceRefreshCompletion.RESULT_SUPERSEDED,
        workSucceeded = true,
    )

    private fun localNetworkPermissionRequired(): SourceRefreshDecision = SourceRefreshDecision(
        state = SourceRefreshRunState.FAILED,
        resultFamily = FAMILY_LOCAL_NETWORK,
        resultCode = "PERMISSION_REQUIRED",
    )

    private fun networkFailure(reason: RemoteSourceNetworkFailureReason): SourceRefreshDecision =
        SourceRefreshDecision(
            state = SourceRefreshRunState.FAILED,
            resultFamily = FAMILY_NETWORK,
            resultCode = reason.stableCode(),
            retryable = reason in RETRYABLE_NETWORK_REASONS,
        )

    private fun importFailure(reason: CatalogImportFailureReason): SourceRefreshDecision =
        SourceRefreshDecision(
            state = SourceRefreshRunState.FAILED,
            resultFamily = FAMILY_IMPORT,
            resultCode = reason.stableCode(),
            retryable = reason == CatalogImportFailureReason.StorageFailure,
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
    private const val FAMILY_LOCAL_NETWORK = "LOCAL_NETWORK"
    private const val FAMILY_TRANSPORT = "TRANSPORT"
    private const val FAMILY_HTTP = "HTTP"
    private const val FAMILY_SIZE = "SIZE"
    private const val FAMILY_REDIRECT = "REDIRECT"
    private const val FAMILY_NETWORK = "NETWORK"
    private const val FAMILY_CONTENT = "CONTENT"
    private const val FAMILY_IMPORT = "IMPORT"
    private const val FAMILY_WORK = "WORK"
    private const val FAMILY_INTERNAL = "INTERNAL"
}

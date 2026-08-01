package app.muxtv.catalog.sync

import app.muxtv.catalog.importer.EpgImportFailureReason
import app.muxtv.catalog.refresh.EpgHttpValidators
import app.muxtv.catalog.refresh.RemoteEpgRefreshResult
import app.muxtv.catalog.refresh.RemoteSourceNetworkFailureReason
import app.muxtv.database.EpgRefreshCompletion
import app.muxtv.database.EpgRefreshHttpValidators
import app.muxtv.database.EpgRefreshRunState

internal sealed interface EpgRefreshDecision {
    val retryable: Boolean
    val workSucceeded: Boolean

    fun toCompletion(
        completedAtEpochMillis: Long,
        accessRefBinding: String,
    ): EpgRefreshCompletion

    data class Refreshed(
        val revisionNumber: Long,
        val channelCount: Int,
        val programmeCount: Int,
        val skippedProgrammeCount: Int,
        val warningCount: Int,
        val unresolvedTimeCount: Int,
        val validators: EpgRefreshHttpValidators,
    ) : EpgRefreshDecision {
        override val retryable: Boolean = false
        override val workSucceeded: Boolean = true

        override fun toCompletion(
            completedAtEpochMillis: Long,
            accessRefBinding: String,
        ): EpgRefreshCompletion = EpgRefreshCompletion.Refreshed(
            completedAtEpochMillis = completedAtEpochMillis,
            accessRefBinding = accessRefBinding,
            revisionNumber = revisionNumber,
            channelCount = channelCount,
            programmeCount = programmeCount,
            skippedProgrammeCount = skippedProgrammeCount,
            warningCount = warningCount,
            unresolvedTimeCount = unresolvedTimeCount,
            validators = validators,
        )
    }

    data class NotModified(
        val validators: EpgRefreshHttpValidators,
    ) : EpgRefreshDecision {
        override val retryable: Boolean = false
        override val workSucceeded: Boolean = true

        override fun toCompletion(
            completedAtEpochMillis: Long,
            accessRefBinding: String,
        ): EpgRefreshCompletion = EpgRefreshCompletion.NotModified(
            completedAtEpochMillis = completedAtEpochMillis,
            accessRefBinding = accessRefBinding,
            validators = validators,
        )
    }

    data class Terminal(
        val state: EpgRefreshRunState,
        val resultFamily: String,
        val resultCode: String?,
        val httpStatus: Int? = null,
        override val retryable: Boolean = false,
        override val workSucceeded: Boolean = false,
    ) : EpgRefreshDecision {
        override fun toCompletion(
            completedAtEpochMillis: Long,
            accessRefBinding: String,
        ): EpgRefreshCompletion = EpgRefreshCompletion.Terminal(
            state = state,
            completedAtEpochMillis = completedAtEpochMillis,
            resultFamily = resultFamily,
            resultCode = resultCode,
            httpStatus = httpStatus,
        )
    }
}

internal object EpgRefreshOutcomeMapper {
    fun map(result: RemoteEpgRefreshResult): EpgRefreshDecision = when (result) {
        is RemoteEpgRefreshResult.Refreshed -> EpgRefreshDecision.Refreshed(
            revisionNumber = result.revisionNumber,
            channelCount = result.channelCount,
            programmeCount = result.programmeCount,
            skippedProgrammeCount = result.skippedProgrammeCount,
            warningCount = result.warningCount,
            unresolvedTimeCount = result.unresolvedTimeCount,
            validators = result.validators.toDatabaseValidators(),
        )

        is RemoteEpgRefreshResult.NotModified -> EpgRefreshDecision.NotModified(
            validators = result.validators.toDatabaseValidators(),
        )

        RemoteEpgRefreshResult.AccessCredentialNotFound -> needsAuth("NOT_FOUND")
        is RemoteEpgRefreshResult.AccessCredentialUnavailable ->
            needsAuth("UNAVAILABLE_${result.reason.stableCode()}")
        RemoteEpgRefreshResult.AccessCredentialCorrupted -> needsAuth("CORRUPTED")

        is RemoteEpgRefreshResult.UrlRejected -> failure(
            family = FAMILY_URL,
            code = result.reason.stableCode(),
        )

        RemoteEpgRefreshResult.InsecureTransportApprovalRequired -> failure(
            family = FAMILY_TRANSPORT,
            code = "INSECURE_APPROVAL_REQUIRED",
        )

        is RemoteEpgRefreshResult.HttpFailure -> mapHttpFailure(result.statusCode)

        is RemoteEpgRefreshResult.ResponseTooLarge -> failure(
            family = FAMILY_SIZE,
            code = result.kind.stableCode(),
        )

        is RemoteEpgRefreshResult.PayloadRejected -> failure(
            family = FAMILY_CONTENT,
            code = result.reason.stableCode(),
        )

        is RemoteEpgRefreshResult.RedirectRejected -> failure(
            family = FAMILY_REDIRECT,
            code = result.reason.stableCode(),
        )

        is RemoteEpgRefreshResult.NetworkFailure -> EpgRefreshDecision.Terminal(
            state = EpgRefreshRunState.FAILED,
            resultFamily = FAMILY_NETWORK,
            resultCode = result.reason.stableCode(),
            retryable = result.reason in RETRYABLE_NETWORK_REASONS,
        )

        RemoteEpgRefreshResult.EmptyRevisionRejected -> failure(
            family = FAMILY_CONTENT,
            code = "EMPTY_REVISION",
        )

        RemoteEpgRefreshResult.Superseded -> superseded()

        is RemoteEpgRefreshResult.ImportFailed -> EpgRefreshDecision.Terminal(
            state = EpgRefreshRunState.FAILED,
            resultFamily = FAMILY_IMPORT,
            resultCode = result.reason.stableCode(),
            retryable = result.reason == EpgImportFailureReason.StorageFailure,
        )
    }

    fun missingAccessReference(): EpgRefreshDecision = needsAuth("MISSING_REFERENCE")

    fun invalidAccessReference(): EpgRefreshDecision = needsAuth("INVALID_REFERENCE")

    fun runtimeTimeout(): EpgRefreshDecision = EpgRefreshDecision.Terminal(
        state = EpgRefreshRunState.FAILED,
        resultFamily = FAMILY_WORK,
        resultCode = "TIMEOUT",
        retryable = true,
    )

    fun cancellation(): EpgRefreshDecision = EpgRefreshDecision.Terminal(
        state = EpgRefreshRunState.CANCELLED,
        resultFamily = FAMILY_WORK,
        resultCode = "CANCELLED",
        workSucceeded = true,
    )

    fun internalFailure(): EpgRefreshDecision = EpgRefreshDecision.Terminal(
        state = EpgRefreshRunState.FAILED,
        resultFamily = FAMILY_INTERNAL,
        resultCode = "UNEXPECTED",
        retryable = true,
    )

    private fun superseded(): EpgRefreshDecision = EpgRefreshDecision.Terminal(
        state = EpgRefreshRunState.CANCELLED,
        resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
        resultCode = EpgRefreshCompletion.RESULT_SUPERSEDED,
        workSucceeded = true,
    )

    private fun mapHttpFailure(statusCode: Int): EpgRefreshDecision = when {
        statusCode == 401 || statusCode == 403 -> EpgRefreshDecision.Terminal(
            state = EpgRefreshRunState.NEEDS_AUTH,
            resultFamily = FAMILY_HTTP,
            resultCode = statusCode.toString(),
            httpStatus = statusCode,
        )

        statusCode in RETRYABLE_HTTP_CODES || statusCode in 500..599 -> EpgRefreshDecision.Terminal(
            state = EpgRefreshRunState.FAILED,
            resultFamily = FAMILY_HTTP,
            resultCode = statusCode.toString(),
            httpStatus = statusCode,
            retryable = true,
        )

        else -> EpgRefreshDecision.Terminal(
            state = EpgRefreshRunState.FAILED,
            resultFamily = FAMILY_HTTP,
            resultCode = statusCode.toString(),
            httpStatus = statusCode,
        )
    }

    private fun needsAuth(code: String): EpgRefreshDecision = EpgRefreshDecision.Terminal(
        state = EpgRefreshRunState.NEEDS_AUTH,
        resultFamily = FAMILY_CREDENTIAL,
        resultCode = code,
    )

    private fun failure(
        family: String,
        code: String,
    ): EpgRefreshDecision = EpgRefreshDecision.Terminal(
        state = EpgRefreshRunState.FAILED,
        resultFamily = family,
        resultCode = code,
    )

    private fun EpgHttpValidators.toDatabaseValidators(): EpgRefreshHttpValidators =
        EpgRefreshHttpValidators(
            etag = etag,
            lastModified = lastModified,
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

    private const val FAMILY_CREDENTIAL = "CREDENTIAL"
    private const val FAMILY_URL = "URL"
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

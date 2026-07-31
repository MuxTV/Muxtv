package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.EpgImportFailureReason
import app.muxtv.catalog.ingest.XmltvParseLimits
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.network.RedirectRejectionReason
import app.muxtv.network.ResponseSizeKind
import app.muxtv.network.ResponseSizeLimits
import app.muxtv.network.SourceUrlRejectionReason

data class EpgHttpValidators(
    val etag: String? = null,
    val lastModified: String? = null,
) {
    init {
        validateOptionalHttpValue(etag, MAX_ETAG_CHARACTERS)
        validateOptionalHttpValue(lastModified, MAX_LAST_MODIFIED_CHARACTERS)
    }

    val isEmpty: Boolean
        get() = etag == null && lastModified == null

    override fun toString(): String =
        "EpgHttpValidators(etagPresent=${etag != null}, lastModifiedPresent=${lastModified != null})"

    internal companion object {
        const val MAX_ETAG_CHARACTERS = 1_024
        const val MAX_LAST_MODIFIED_CHARACTERS = 256

        fun fromResponse(
            etag: String?,
            lastModified: String?,
        ): EpgHttpValidators = EpgHttpValidators(
            etag = etag.validResponseValueOrNull(MAX_ETAG_CHARACTERS),
            lastModified = lastModified.validResponseValueOrNull(MAX_LAST_MODIFIED_CHARACTERS),
        )
    }
}

data class RemoteEpgRefreshRequest(
    val sourceId: String,
    val sourceName: String,
    val providerSourceId: String?,
    val accessCredentialId: CredentialId,
    val defaultZoneId: String?,
    val validators: EpgHttpValidators = EpgHttpValidators(),
    val responseSizeLimits: ResponseSizeLimits = ResponseSizeLimits(),
    val decodeLimits: EpgPayloadDecodeLimits = EpgPayloadDecodeLimits(),
    val parseLimits: XmltvParseLimits = XmltvParseLimits(),
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(providerSourceId == null || providerSourceId.isNotBlank())
        require(defaultZoneId == null || defaultZoneId.isNotBlank())
    }

    override fun toString(): String =
        "RemoteEpgRefreshRequest(providerLinked=${providerSourceId != null}, " +
            "defaultZonePresent=${defaultZoneId != null}, validators=$validators)"
}

sealed interface RemoteEpgRefreshResult {
    data class Refreshed(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val channelCount: Int,
        val programmeCount: Int,
        val skippedProgrammeCount: Int,
        val warningCount: Int,
        val unresolvedTimeCount: Int,
        val payloadFormat: EpgPayloadFormat,
        val validators: EpgHttpValidators,
    ) : RemoteEpgRefreshResult

    data class NotModified(
        val validators: EpgHttpValidators,
    ) : RemoteEpgRefreshResult

    data object AccessCredentialNotFound : RemoteEpgRefreshResult

    data class AccessCredentialUnavailable(
        val reason: CredentialUnavailableReason,
    ) : RemoteEpgRefreshResult

    data object AccessCredentialCorrupted : RemoteEpgRefreshResult

    data class UrlRejected(
        val reason: SourceUrlRejectionReason,
    ) : RemoteEpgRefreshResult

    data object InsecureTransportApprovalRequired : RemoteEpgRefreshResult

    data class HttpFailure(
        val statusCode: Int,
    ) : RemoteEpgRefreshResult

    data class ResponseTooLarge(
        val kind: ResponseSizeKind,
        val limitBytes: Long,
    ) : RemoteEpgRefreshResult

    data class PayloadRejected(
        val reason: EpgPayloadRejectionReason,
    ) : RemoteEpgRefreshResult

    data class RedirectRejected(
        val reason: RedirectRejectionReason,
    ) : RemoteEpgRefreshResult

    data class NetworkFailure(
        val reason: RemoteSourceNetworkFailureReason,
    ) : RemoteEpgRefreshResult

    data object EmptyRevisionRejected : RemoteEpgRefreshResult
    data object Superseded : RemoteEpgRefreshResult

    data class ImportFailed(
        val reason: EpgImportFailureReason,
    ) : RemoteEpgRefreshResult
}

private fun validateOptionalHttpValue(
    value: String?,
    maxCharacters: Int,
) {
    if (value == null) return
    require(value.isNotBlank()) { "HTTP validator value must not be blank." }
    require(value.length <= maxCharacters) { "HTTP validator value is too long." }
    require(value.none { character -> character.code < 0x20 || character.code == 0x7f }) {
        "HTTP validator value contains control characters."
    }
}

private fun String?.validResponseValueOrNull(maxCharacters: Int): String? {
    if (this == null || isBlank() || length > maxCharacters) return null
    if (any { character -> character.code < 0x20 || character.code == 0x7f }) return null
    return this
}
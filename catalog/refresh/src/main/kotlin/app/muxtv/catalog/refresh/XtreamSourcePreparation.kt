package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import app.muxtv.network.SourceUrlRejectionReason
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class XtreamSourcePreparationInput(
    val endpoint: String,
    val username: String,
    val password: String,
    val insecureHttpApproved: Boolean = false,
) {
    override fun toString(): String =
        "XtreamSourcePreparationInput(endpoint=<redacted>, username=<redacted>, " +
            "password=<redacted>, insecureHttpApproved=$insecureHttpApproved)"
}

sealed interface XtreamSourcePreparationResult {
    data class Prepared(
        val accessReference: SourceAccessReference,
        val scheme: String,
        val host: String,
    ) : XtreamSourcePreparationResult {
        init {
            require(accessReference.kind == SourceAccessKind.XTREAM)
            require(scheme == "http" || scheme == "https")
            require(host.isNotBlank())
        }
    }

    data class UrlRejected(
        val reason: SourceUrlRejectionReason,
    ) : XtreamSourcePreparationResult

    data object InsecureTransportApprovalRequired : XtreamSourcePreparationResult
    data object InvalidAccess : XtreamSourcePreparationResult

    data class CredentialTooLarge(
        val limitBytes: Int,
    ) : XtreamSourcePreparationResult

    data class CredentialUnavailable(
        val reason: CredentialUnavailableReason,
    ) : XtreamSourcePreparationResult
}

class XtreamSourcePreparer(
    private val accessManager: XtreamSourceAccessManager,
) {
    suspend fun prepare(input: XtreamSourcePreparationInput): XtreamSourcePreparationResult {
        val normalizedUrl = when (val decision = SourceUrlPolicy.evaluate(input.endpoint)) {
            is SourceUrlDecision.Allowed -> decision.normalizedUrl
            is SourceUrlDecision.RequiresInsecureTransportApproval -> {
                if (!input.insecureHttpApproved) {
                    return XtreamSourcePreparationResult.InsecureTransportApprovalRequired
                }
                decision.normalizedUrl
            }

            is SourceUrlDecision.Rejected ->
                return XtreamSourcePreparationResult.UrlRejected(decision.reason)
        }

        val baseEndpoint = normalizedUrl.toXtreamBaseEndpoint()
        val access = try {
            XtreamSourceAccess(
                baseUrl = baseEndpoint.toString(),
                username = input.username,
                password = input.password,
                insecureHttpApproved = input.insecureHttpApproved,
            )
        } catch (_: IllegalArgumentException) {
            return XtreamSourcePreparationResult.InvalidAccess
        }

        val credentialId = CredentialId.random()
        return when (val stored = accessManager.save(credentialId, access)) {
            CredentialWriteResult.Stored -> XtreamSourcePreparationResult.Prepared(
                accessReference = SourceAccessReference.xtream(credentialId),
                scheme = baseEndpoint.scheme,
                host = baseEndpoint.host,
            )

            is CredentialWriteResult.RejectedTooLarge ->
                XtreamSourcePreparationResult.CredentialTooLarge(stored.limitBytes)

            is CredentialWriteResult.Unavailable ->
                XtreamSourcePreparationResult.CredentialUnavailable(stored.reason)
        }
    }

    suspend fun rollback(accessReference: SourceAccessReference): CredentialRemoveResult {
        require(accessReference.kind == SourceAccessKind.XTREAM) {
            "Xtream rollback requires an Xtream source access reference."
        }
        return accessManager.remove(accessReference.credentialId)
    }
}

private fun String.toXtreamBaseEndpoint(): HttpUrl {
    val url = toHttpUrl()
    val builder = url.newBuilder().query(null)
    val lastPathSegment = url.pathSegments.lastOrNull()
    if (lastPathSegment.equals(PLAYER_API_PATH, ignoreCase = true)) {
        builder.removePathSegment(url.pathSegments.lastIndex)
    }
    return builder.build()
}

private const val PLAYER_API_PATH = "player_api.php"

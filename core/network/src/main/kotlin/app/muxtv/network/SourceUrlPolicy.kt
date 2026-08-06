package app.muxtv.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface SourceUrlDecision {
    data class Allowed(
        val normalizedUrl: String,
    ) : SourceUrlDecision

    data class RequiresInsecureTransportApproval(
        val normalizedUrl: String,
    ) : SourceUrlDecision

    data class Rejected(
        val reason: SourceUrlRejectionReason,
    ) : SourceUrlDecision
}

enum class SourceUrlRejectionReason {
    Empty,
    Malformed,
    UnsupportedScheme,
    EmbeddedCredentials,
    ControlSeparator,
    Fragment,
}

object SourceUrlPolicy {
    fun evaluate(rawUrl: String): SourceUrlDecision {
        val candidate = rawUrl.trim()
        if (candidate.isEmpty()) {
            return SourceUrlDecision.Rejected(SourceUrlRejectionReason.Empty)
        }
        if (containsControlSeparator(candidate)) {
            return SourceUrlDecision.Rejected(SourceUrlRejectionReason.ControlSeparator)
        }

        val schemeSeparator = candidate.indexOf("://")
        val normalizedCandidate = if (schemeSeparator >= 0) {
            if (schemeSeparator == 0) {
                return SourceUrlDecision.Rejected(SourceUrlRejectionReason.Malformed)
            }
            val rawScheme = candidate.substring(0, schemeSeparator)
            if (!SCHEME.matches(rawScheme)) {
                return SourceUrlDecision.Rejected(SourceUrlRejectionReason.Malformed)
            }
            if (!rawScheme.equals("http", ignoreCase = true) &&
                !rawScheme.equals("https", ignoreCase = true)
            ) {
                return SourceUrlDecision.Rejected(SourceUrlRejectionReason.UnsupportedScheme)
            }
            candidate
        } else {
            "https://$candidate"
        }

        val url = normalizedCandidate.toHttpUrlOrNull()
            ?: return SourceUrlDecision.Rejected(SourceUrlRejectionReason.Malformed)

        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return SourceUrlDecision.Rejected(SourceUrlRejectionReason.EmbeddedCredentials)
        }
        if (url.fragment != null) {
            return SourceUrlDecision.Rejected(SourceUrlRejectionReason.Fragment)
        }

        val normalizedUrl = url.toString()
        return if (url.isHttps) {
            SourceUrlDecision.Allowed(normalizedUrl)
        } else {
            SourceUrlDecision.RequiresInsecureTransportApproval(normalizedUrl)
        }
    }

    internal fun containsControlSeparator(value: String): Boolean {
        var candidate = value
        repeat(MAX_ENCODING_LAYERS + 1) {
            if (candidate.any { it == '\r' || it == '\n' || it == '\t' }) {
                return true
            }
            if (ENCODED_CONTROL_SEPARATOR.containsMatchIn(candidate)) {
                return true
            }
            candidate = candidate.replace("%25", "%", ignoreCase = true)
        }
        return false
    }

    private const val MAX_ENCODING_LAYERS = 2
    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
    private val ENCODED_CONTROL_SEPARATOR = Regex("%(?:0a|0d|09)", RegexOption.IGNORE_CASE)
}

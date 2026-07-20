package app.muxtv.network

import okhttp3.HttpUrl

sealed interface RedirectDecision {
    data class Follow(
        val targetUrl: HttpUrl,
        val headerDisposition: RedirectHeaderDisposition,
    ) : RedirectDecision

    data class Rejected(
        val reason: RedirectRejectionReason,
    ) : RedirectDecision
}

enum class RedirectHeaderDisposition {
    Preserve,
    StripSensitive,
}

enum class RedirectRejectionReason {
    MissingLocation,
    TooManyRedirects,
    MalformedLocation,
    EmbeddedCredentials,
    ControlSeparator,
    Fragment,
    HttpsDowngrade,
    InsecureTransportNotApproved,
}

object RedirectPolicy {
    const val MAX_REDIRECTS = 5

    fun evaluate(
        currentUrl: HttpUrl,
        location: String?,
        completedRedirects: Int,
        insecureHttpApproved: Boolean,
    ): RedirectDecision {
        if (completedRedirects >= MAX_REDIRECTS) {
            return RedirectDecision.Rejected(RedirectRejectionReason.TooManyRedirects)
        }

        val candidate = location?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return RedirectDecision.Rejected(RedirectRejectionReason.MissingLocation)
        }
        if (SourceUrlPolicy.containsControlSeparator(candidate)) {
            return RedirectDecision.Rejected(RedirectRejectionReason.ControlSeparator)
        }

        val targetUrl = currentUrl.resolve(candidate)
            ?: return RedirectDecision.Rejected(RedirectRejectionReason.MalformedLocation)

        if (targetUrl.username.isNotEmpty() || targetUrl.password.isNotEmpty()) {
            return RedirectDecision.Rejected(RedirectRejectionReason.EmbeddedCredentials)
        }
        if (targetUrl.fragment != null) {
            return RedirectDecision.Rejected(RedirectRejectionReason.Fragment)
        }
        if (currentUrl.isHttps && !targetUrl.isHttps) {
            return RedirectDecision.Rejected(RedirectRejectionReason.HttpsDowngrade)
        }
        if (!targetUrl.isHttps && !insecureHttpApproved) {
            return RedirectDecision.Rejected(RedirectRejectionReason.InsecureTransportNotApproved)
        }

        val sameOrigin = currentUrl.scheme == targetUrl.scheme &&
            currentUrl.host == targetUrl.host &&
            currentUrl.port == targetUrl.port

        return RedirectDecision.Follow(
            targetUrl = targetUrl,
            headerDisposition = if (sameOrigin) {
                RedirectHeaderDisposition.Preserve
            } else {
                RedirectHeaderDisposition.StripSensitive
            },
        )
    }
}

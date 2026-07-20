package app.muxtv.network

import okhttp3.Headers

object SensitiveHeaderPolicy {
    fun apply(
        headers: Headers,
        disposition: RedirectHeaderDisposition,
    ): Headers {
        if (disposition == RedirectHeaderDisposition.Preserve) {
            return headers
        }

        val sanitized = Headers.Builder()
        repeat(headers.size) { index ->
            val name = headers.name(index)
            if (name.lowercase() !in SENSITIVE_HEADER_NAMES) {
                sanitized.add(name, headers.value(index))
            }
        }
        return sanitized.build()
    }

    private val SENSITIVE_HEADER_NAMES = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "cookie2",
        "referer",
        "origin",
        "x-api-key",
        "x-auth-token",
        "x-access-token",
    )
}

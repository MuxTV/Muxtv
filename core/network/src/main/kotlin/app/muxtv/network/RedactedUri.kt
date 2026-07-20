package app.muxtv.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@JvmInline
value class RedactedUri private constructor(
    private val value: String,
) {
    override fun toString(): String = value

    companion object {
        fun from(rawUrl: String): RedactedUri {
            val parsed = rawUrl.trim().toHttpUrlOrNull()
                ?: return RedactedUri(INVALID_URL)

            val builder = parsed.newBuilder()
                .username("")
                .password("")
                .query(null)

            repeat(parsed.querySize) { index ->
                val name = parsed.queryParameterName(index)
                val value = parsed.queryParameterValue(index)
                builder.addQueryParameter(
                    name,
                    if (name.isSensitiveQueryName()) REDACTED_VALUE else value,
                )
            }

            return RedactedUri(builder.build().toString())
        }

        private fun String.isSensitiveQueryName(): Boolean =
            lowercase()
                .filter(Char::isLetterOrDigit) in SENSITIVE_QUERY_NAMES

        private const val INVALID_URL = "<invalid-url>"
        private const val REDACTED_VALUE = "<redacted>"

        private val SENSITIVE_QUERY_NAMES = setOf(
            "token",
            "accesstoken",
            "refreshtoken",
            "password",
            "passwd",
            "secret",
            "key",
            "apikey",
            "auth",
            "authorization",
            "credential",
            "credentials",
            "signature",
            "sig",
            "username",
            "user",
            "login",
        )
    }
}

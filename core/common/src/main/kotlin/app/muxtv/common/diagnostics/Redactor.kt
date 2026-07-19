package app.muxtv.common.diagnostics

object Redactor {
    private val userInfo = Regex("(?i)(https?://)[^/@\\s]+@")
    private val sensitiveParameter = Regex(
        "(?i)([?&\\s](?:token|access_token|refresh_token|password|passwd|secret|api_key|apikey|auth)=)[^&\\s]+",
    )
    private val authorizationHeader = Regex("(?i)(Authorization\\s*:\\s*)[^\\r\\n]+?(?=\\s+Cookie\\s*:|$)")
    private val cookieHeader = Regex("(?i)(Cookie\\s*:\\s*)[^\\r\\n]+$")

    fun redactText(value: String): String = value
        .replace(userInfo, "$1[redacted]@")
        .replace(sensitiveParameter) { match -> "${match.groupValues[1]}[redacted]" }
        .replace(authorizationHeader, "$1[redacted]")
        .replace(cookieHeader, "$1[redacted]")
}

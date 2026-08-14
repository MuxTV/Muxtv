package app.muxtv.external

import java.net.URI

/**
 * Normalized exact playback origin: `scheme://host:port` with default ports folded.
 *
 * Origins never contain path, query, userinfo or fragment. A torrent hash, file index or any
 * other stream identifier must never be part of an origin grant.
 */
data class ExternalPlaybackOrigin(
    val scheme: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
) {
    init {
        require(scheme == "http" || scheme == "https")
        require(host.isNotBlank())
        require(port == DEFAULT_PORT || port in 1..65535)
    }

    val encoded: String
        get() = if (port == DEFAULT_PORT) "$scheme://$host" else "$scheme://$host:$port"

    val isCleartext: Boolean
        get() = scheme == "http"

    override fun toString(): String =
        "ExternalPlaybackOrigin(scheme=$scheme, host=<redacted>, port=$port)"

    companion object {
        const val DEFAULT_PORT = -1

        fun parse(raw: String): ExternalPlaybackOrigin? {
            if (raw.isBlank()) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null) return null
            if (!uri.path.isNullOrEmpty()) return null
            if (uri.query != null || uri.fragment != null) return null
            val host = normalizedHost(uri.host) ?: return null
            return fromParts(scheme, host, uri.port)
        }

        fun fromLocator(locator: String): ExternalPlaybackOrigin? {
            val uri = runCatching { URI(locator) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null) return null
            val host = normalizedHost(uri.host) ?: return null
            return fromParts(scheme, host, uri.port)
        }

        private fun fromParts(
            scheme: String,
            host: String,
            explicitPort: Int,
        ): ExternalPlaybackOrigin? {
            if (explicitPort != DEFAULT_PORT && explicitPort !in 1..65535) return null
            val defaultPort = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> return null
            }
            val port = if (explicitPort == DEFAULT_PORT || explicitPort == defaultPort) {
                DEFAULT_PORT
            } else {
                explicitPort
            }
            return ExternalPlaybackOrigin(scheme = scheme, host = host, port = port)
        }

        private fun normalizedHost(host: String?): String? = host
            ?.trim()
            ?.lowercase()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.takeIf(String::isNotBlank)
    }
}

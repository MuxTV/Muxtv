package app.muxtv.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@JvmInline
value class ExactHttpOrigin private constructor(
    private val canonical: String,
) {
    fun encoded(): String = canonical

    fun displayValue(): String = canonical

    override fun toString(): String = "ExactHttpOrigin(<redacted>)"

    companion object {
        fun fromUrl(url: String): ExactHttpOrigin? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != HTTP_SCHEME) return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null

            val displayHost = if (':' in parsed.host) {
                "[${parsed.host}]"
            } else {
                parsed.host
            }
            return ExactHttpOrigin("$HTTP_SCHEME://$displayHost:${parsed.port}")
        }

        fun parse(encoded: String): ExactHttpOrigin? {
            val origin = fromUrl(encoded) ?: return null
            return origin.takeIf { it.canonical == encoded }
        }

        private const val HTTP_SCHEME = "http"
    }
}

package app.muxtv.model

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

enum class ProviderRequestTarget {
    MANIFEST,
    SEGMENT,
}

sealed interface ProviderRequestHeaderDecision {
    data class Accepted(val canonicalName: String) : ProviderRequestHeaderDecision
    data object Forbidden : ProviderRequestHeaderDecision
    data object Unsupported : ProviderRequestHeaderDecision
    data object Malformed : ProviderRequestHeaderDecision
}

object ProviderRequestHeaderPolicy {
    const val MAX_HEADER_NAME_CHARACTERS: Int = 64
    const val MAX_HEADER_VALUE_CHARACTERS: Int = 8_192

    fun canonicalize(rawName: String): ProviderRequestHeaderDecision {
        val name = rawName.trim()
        if (!name.isValidHeaderName()) return ProviderRequestHeaderDecision.Malformed
        val normalized = name.lowercase(Locale.ROOT)
        CANONICAL_NAMES[normalized]?.let { canonical ->
            return ProviderRequestHeaderDecision.Accepted(canonical)
        }
        return if (normalized in FORBIDDEN_NAMES) {
            ProviderRequestHeaderDecision.Forbidden
        } else {
            ProviderRequestHeaderDecision.Unsupported
        }
    }

    private fun String.isValidHeaderName(): Boolean {
        if (isEmpty() || length > MAX_HEADER_NAME_CHARACTERS) return false
        return all { character -> character in HTTP_TOKEN_CHARACTERS }
    }

    private val CANONICAL_NAMES = mapOf(
        "user-agent" to "User-Agent",
        "referer" to "Referer",
        "referrer" to "Referer",
        "origin" to "Origin",
        "authorization" to "Authorization",
        "cookie" to "Cookie",
        "x-api-key" to "X-Api-Key",
        "x-auth-token" to "X-Auth-Token",
        "x-access-token" to "X-Access-Token",
    )

    private val FORBIDDEN_NAMES = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "range",
        "proxy-authorization",
    )

    private val HTTP_TOKEN_CHARACTERS: Set<Char> = buildSet {
        addAll('a'..'z')
        addAll('A'..'Z')
        addAll('0'..'9')
        addAll("!#$%&'*+-.^_`|~".toList())
    }
}

class ProviderRequestMetadata(
    defaultHeaders: Map<String, String> = emptyMap(),
    manifestHeaders: Map<String, String> = emptyMap(),
    segmentHeaders: Map<String, String> = emptyMap(),
) {
    val defaultHeaders: Map<String, String> = normalizeScope(defaultHeaders)
    val manifestHeaders: Map<String, String> = normalizeScope(manifestHeaders)
    val segmentHeaders: Map<String, String> = normalizeScope(segmentHeaders)

    private val resolvedManifestHeaders: Map<String, String> = mergeScopes(
        defaultHeaders = this.defaultHeaders,
        targetHeaders = this.manifestHeaders,
    )
    private val resolvedSegmentHeaders: Map<String, String> = mergeScopes(
        defaultHeaders = this.defaultHeaders,
        targetHeaders = this.segmentHeaders,
    )

    init {
        val acceptedHeaderCount = this.defaultHeaders.size +
            this.manifestHeaders.size +
            this.segmentHeaders.size
        require(acceptedHeaderCount <= MAX_ACCEPTED_HEADERS) {
            "Provider request metadata exceeds the accepted header count."
        }
    }

    fun headersFor(target: ProviderRequestTarget): Map<String, String> = when (target) {
        ProviderRequestTarget.MANIFEST -> resolvedManifestHeaders
        ProviderRequestTarget.SEGMENT -> resolvedSegmentHeaders
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderRequestMetadata) return false
        return defaultHeaders == other.defaultHeaders &&
            manifestHeaders == other.manifestHeaders &&
            segmentHeaders == other.segmentHeaders
    }

    override fun hashCode(): Int {
        var result = defaultHeaders.hashCode()
        result = 31 * result + manifestHeaders.hashCode()
        result = 31 * result + segmentHeaders.hashCode()
        return result
    }

    override fun toString(): String =
        "ProviderRequestMetadata(defaultHeaderCount=${defaultHeaders.size}, " +
            "manifestHeaderCount=${manifestHeaders.size}, " +
            "segmentHeaderCount=${segmentHeaders.size})"

    companion object {
        const val MAX_ACCEPTED_HEADERS: Int = 32
        val EMPTY: ProviderRequestMetadata = ProviderRequestMetadata()

        private fun normalizeScope(headers: Map<String, String>): Map<String, String> {
            if (headers.isEmpty()) return emptyMap()
            val normalized = LinkedHashMap<String, String>(headers.size)
            headers.forEach { (rawName, rawValue) ->
                val canonicalName = when (
                    val decision = ProviderRequestHeaderPolicy.canonicalize(rawName)
                ) {
                    is ProviderRequestHeaderDecision.Accepted -> decision.canonicalName
                    ProviderRequestHeaderDecision.Forbidden,
                    ProviderRequestHeaderDecision.Malformed,
                    ProviderRequestHeaderDecision.Unsupported,
                    -> throw IllegalArgumentException("Provider request header is not allowed.")
                }
                validateHeaderValue(rawValue)
                normalized[canonicalName] = rawValue
            }
            return Collections.unmodifiableMap(normalized)
        }

        private fun mergeScopes(
            defaultHeaders: Map<String, String>,
            targetHeaders: Map<String, String>,
        ): Map<String, String> {
            if (targetHeaders.isEmpty()) return defaultHeaders
            if (defaultHeaders.isEmpty()) return targetHeaders
            val merged = LinkedHashMap<String, String>(defaultHeaders.size + targetHeaders.size)
            merged.putAll(defaultHeaders)
            merged.putAll(targetHeaders)
            return Collections.unmodifiableMap(merged)
        }

        private fun validateHeaderValue(value: String) {
            require(value.isNotEmpty()) { "Provider request header value must not be empty." }
            require(value.length <= ProviderRequestHeaderPolicy.MAX_HEADER_VALUE_CHARACTERS) {
                "Provider request header value is too long."
            }
            require(value.none { character ->
                character == '\r' || character == '\n' || character == '\u0000'
            }) {
                "Provider request header value contains a prohibited control character."
            }
        }
    }
}

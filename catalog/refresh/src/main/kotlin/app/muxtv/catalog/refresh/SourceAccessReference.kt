package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId

enum class SourceAccessKind {
    M3U,
    XTREAM,
}

/**
 * Opaque durable binding between a catalog source and its encrypted access record.
 *
 * Legacy M3U rows stored a bare credential UUID. They remain valid and preserve their original
 * value. New typed references carry only routing metadata plus the credential identifier; secrets
 * remain exclusively inside CredentialStore.
 */
class SourceAccessReference private constructor(
    val kind: SourceAccessKind,
    val credentialId: CredentialId,
    val value: String,
) {
    override fun toString(): String =
        "SourceAccessReference(kind=$kind, credentialId=<redacted>)"

    companion object {
        private const val ROOT_PREFIX = "muxtv-access:"
        private const val VERSION_PREFIX = "muxtv-access:v1:"
        private const val M3U_KIND = "m3u"
        private const val XTREAM_KIND = "xtream"
        private const val INVALID_REFERENCE_MESSAGE = "Source access reference is invalid."

        fun m3u(credentialId: CredentialId): SourceAccessReference =
            typed(SourceAccessKind.M3U, M3U_KIND, credentialId)

        fun xtream(credentialId: CredentialId): SourceAccessReference =
            typed(SourceAccessKind.XTREAM, XTREAM_KIND, credentialId)

        fun parse(value: String): SourceAccessReference {
            if (!value.startsWith(ROOT_PREFIX)) {
                val credentialId = parseCredentialId(value)
                return SourceAccessReference(
                    kind = SourceAccessKind.M3U,
                    credentialId = credentialId,
                    value = credentialId.value,
                )
            }

            require(value.startsWith(VERSION_PREFIX)) { INVALID_REFERENCE_MESSAGE }
            val encoded = value.removePrefix(VERSION_PREFIX)
            val separator = encoded.indexOf(':')
            require(separator > 0 && separator == encoded.lastIndexOf(':')) {
                INVALID_REFERENCE_MESSAGE
            }

            val kind = when (encoded.substring(0, separator)) {
                M3U_KIND -> SourceAccessKind.M3U
                XTREAM_KIND -> SourceAccessKind.XTREAM
                else -> throw IllegalArgumentException(INVALID_REFERENCE_MESSAGE)
            }
            val credentialId = parseCredentialId(encoded.substring(separator + 1))
            return SourceAccessReference(
                kind = kind,
                credentialId = credentialId,
                value = value,
            )
        }

        private fun typed(
            kind: SourceAccessKind,
            encodedKind: String,
            credentialId: CredentialId,
        ): SourceAccessReference = SourceAccessReference(
            kind = kind,
            credentialId = credentialId,
            value = "$VERSION_PREFIX$encodedKind:${credentialId.value}",
        )

        private fun parseCredentialId(value: String): CredentialId =
            try {
                CredentialId.parse(value)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(INVALID_REFERENCE_MESSAGE, error)
            }
    }
}

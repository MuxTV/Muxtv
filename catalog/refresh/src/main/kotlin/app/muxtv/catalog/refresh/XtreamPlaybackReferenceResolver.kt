package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.credentials.CredentialId
import app.muxtv.network.ExactHttpOrigin
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class XtreamPlaybackReferenceResolver(
    private val manager: XtreamSourceAccessManager,
) : PlaybackReferenceResolver {
    override suspend fun resolve(request: PlaybackReferenceRequest): PlaybackReferenceResolution {
        val parsed = parseReference(request.playbackReference)
            ?: return if (request.playbackReference.startsWith(PROVIDER_REFERENCE_PREFIX)) {
                PlaybackReferenceResolution.InvalidReference
            } else {
                PlaybackReferenceResolution.Unhandled
            }

        val credentialId = try {
            CredentialId.parse(request.credentialRef)
        } catch (_: IllegalArgumentException) {
            return PlaybackReferenceResolution.CredentialNotFound
        }

        val access = when (val result = manager.read(credentialId)) {
            is XtreamSourceAccessReadResult.Found -> result.access
            XtreamSourceAccessReadResult.NotFound -> return PlaybackReferenceResolution.CredentialNotFound
            XtreamSourceAccessReadResult.Corrupted -> return PlaybackReferenceResolution.CredentialCorrupted
            is XtreamSourceAccessReadResult.Unavailable -> return PlaybackReferenceResolution.CredentialUnavailable
        }

        val normalizedBaseUrl = when (val decision = SourceUrlPolicy.evaluate(access.baseUrl)) {
            is SourceUrlDecision.Allowed -> decision.normalizedUrl
            is SourceUrlDecision.RequiresInsecureTransportApproval -> {
                if (!access.insecureHttpApproved) {
                    val displayOrigin = ExactHttpOrigin.fromUrl(decision.normalizedUrl)?.displayValue()
                        ?: return PlaybackReferenceResolution.InvalidReference
                    return PlaybackReferenceResolution.ApprovalRequired(displayOrigin)
                }
                decision.normalizedUrl
            }
            is SourceUrlDecision.Rejected -> return PlaybackReferenceResolution.InvalidReference
        }

        val locator = normalizedBaseUrl.toHttpUrl().toLiveUrl(
            username = access.username,
            password = access.password,
            streamId = parsed.streamId,
            format = parsed.format,
        )

        return PlaybackReferenceResolution.Ready(
            locator = locator.toString(),
            insecureHttpPreapproved = !locator.isHttps && access.insecureHttpApproved,
        )
    }

    private fun parseReference(reference: String): ParsedXtreamLiveReference? {
        if (reference.length > MAX_REFERENCE_CHARACTERS) return null
        val match = LIVE_REFERENCE.matchEntire(reference) ?: return null
        val streamId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val format = match.groupValues[2].ifEmpty { LEGACY_FORMAT }
        return ParsedXtreamLiveReference(streamId, format)
    }

    private fun HttpUrl.toLiveUrl(
        username: String,
        password: String,
        streamId: Long,
        format: String,
    ): HttpUrl = newBuilder()
        .query(null)
        .fragment(null)
        .addPathSegment("live")
        .addPathSegment(username)
        .addPathSegment(password)
        .addPathSegment("$streamId.$format")
        .build()

    private data class ParsedXtreamLiveReference(
        val streamId: Long,
        val format: String,
    )

    private companion object {
        const val PROVIDER_REFERENCE_PREFIX = "muxtv-provider://"
        const val MAX_REFERENCE_CHARACTERS = 256
        const val LEGACY_FORMAT = "ts"
        val LIVE_REFERENCE = Regex(
            "^muxtv-provider://xtream/live/([1-9][0-9]{0,18})(?:/(ts|m3u8))?$",
        )
    }
}

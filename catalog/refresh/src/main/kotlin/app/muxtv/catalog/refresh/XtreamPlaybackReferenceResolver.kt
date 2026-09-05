package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.credentials.CredentialId
import app.muxtv.network.ExactHttpOrigin
import app.muxtv.network.SourceUrlDecision
import app.muxtv.network.SourceUrlPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

        val baseUrl = normalizedBaseUrl.toHttpUrl()
        val locator = when (parsed) {
            is ParsedXtreamLiveReference -> baseUrl.toLiveUrl(
                username = access.username,
                password = access.password,
                streamId = parsed.streamId,
                format = parsed.format,
            )

            is ParsedXtreamArchiveReference -> {
                val archiveTimeZone = access.archiveTimeZoneId
                    ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                    ?: return PlaybackReferenceResolution.InvalidReference
                baseUrl.toArchiveUrl(
                    username = access.username,
                    password = access.password,
                    streamId = parsed.streamId,
                    durationMinutes = parsed.durationMinutes,
                    startEpochMillis = parsed.startEpochMillis,
                    archiveTimeZone = archiveTimeZone,
                    format = parsed.format,
                )
            }
        }

        return PlaybackReferenceResolution.Ready(
            locator = locator.toString(),
            insecureHttpPreapproved = !locator.isHttps && access.insecureHttpApproved,
        )
    }

    private fun parseReference(reference: String): ParsedXtreamReference? {
        if (reference.length > MAX_REFERENCE_CHARACTERS) return null

        LIVE_REFERENCE.matchEntire(reference)?.let { match ->
            val streamId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val format = match.groupValues[2].ifEmpty { LEGACY_FORMAT }
            return ParsedXtreamLiveReference(streamId, format)
        }

        val archiveMatch = ARCHIVE_REFERENCE.matchEntire(reference) ?: return null
        val streamId = archiveMatch.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val durationMinutes = archiveMatch.groupValues[2]
            .toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return null
        val startEpochMillis = archiveMatch.groupValues[3]
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        val format = archiveMatch.groupValues[4]
        return ParsedXtreamArchiveReference(
            streamId = streamId,
            durationMinutes = durationMinutes,
            startEpochMillis = startEpochMillis,
            format = format,
        )
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

    private fun HttpUrl.toArchiveUrl(
        username: String,
        password: String,
        streamId: Long,
        durationMinutes: Int,
        startEpochMillis: Long,
        archiveTimeZone: ZoneId,
        format: String,
    ): HttpUrl = newBuilder()
        .query(null)
        .fragment(null)
        .addPathSegment("timeshift")
        .addPathSegment(username)
        .addPathSegment(password)
        .addPathSegment(durationMinutes.toString())
        .addPathSegment(
            ARCHIVE_START_FORMATTER.format(
                Instant.ofEpochMilli(startEpochMillis).atZone(archiveTimeZone),
            ),
        )
        .addPathSegment("$streamId.$format")
        .build()

    private sealed interface ParsedXtreamReference {
        val streamId: Long
        val format: String
    }

    private data class ParsedXtreamLiveReference(
        override val streamId: Long,
        override val format: String,
    ) : ParsedXtreamReference

    private data class ParsedXtreamArchiveReference(
        override val streamId: Long,
        val durationMinutes: Int,
        val startEpochMillis: Long,
        override val format: String,
    ) : ParsedXtreamReference

    private companion object {
        const val PROVIDER_REFERENCE_PREFIX = "muxtv-provider://"
        const val MAX_REFERENCE_CHARACTERS = 256
        const val LEGACY_FORMAT = "ts"
        val ARCHIVE_START_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT)
        val LIVE_REFERENCE = Regex(
            "^muxtv-provider://xtream/live/([1-9][0-9]{0,18})(?:/(ts|m3u8))?$",
        )
        val ARCHIVE_REFERENCE = Regex(
            "^muxtv-provider://xtream/archive/([1-9][0-9]{0,18})/" +
                "([1-9][0-9]{0,9})/([1-9][0-9]{0,18})/(ts|m3u8)$",
        )
    }
}

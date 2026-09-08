package app.muxtv.catalog.refresh

import app.muxtv.catalog.ingest.M3uParseLimits
import app.muxtv.player.PlaybackIntent
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal sealed interface M3uCatchupDialect {
    val granularityMillis: Long

    class Append internal constructor(
        internal val template: M3uCatchupTemplate,
    ) : M3uCatchupDialect {
        override val granularityMillis: Long = SECOND_MILLIS
        override fun toString(): String = "M3uCatchupDialect.Append(template=<redacted>)"
    }

    data object Shift : M3uCatchupDialect {
        override val granularityMillis: Long = SECOND_MILLIS
    }

    data object FlussonicHls : M3uCatchupDialect {
        override val granularityMillis: Long = SECOND_MILLIS
    }

    data object FlussonicTs : M3uCatchupDialect {
        override val granularityMillis: Long = SECOND_MILLIS
    }

    data object XtreamPhp : M3uCatchupDialect {
        override val granularityMillis: Long = MINUTE_MILLIS
    }
}

internal sealed interface M3uCatchupDialectSelection {
    data class Supported(
        val dialect: M3uCatchupDialect,
    ) : M3uCatchupDialectSelection

    data class Unavailable(
        val reason: M3uCatchupUnavailableReason,
    ) : M3uCatchupDialectSelection
}

internal fun selectM3uCatchupDialect(
    intent: PlaybackIntent,
    metadata: M3uCatchupMetadata,
): M3uCatchupDialectSelection {
    val mode = metadata.mode
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)
        ?: return unavailableDialect(M3uCatchupUnavailableReason.UNSUPPORTED_MODE)

    val hasSource = !metadata.source.isNullOrBlank()
    return when (mode) {
        MODE_APPEND -> {
            val rawTemplate = metadata.source
                ?: return unavailableDialect(M3uCatchupUnavailableReason.INVALID_METADATA)
            val template = M3uCatchupTemplate.compile(rawTemplate)
                ?: return unavailableDialect(M3uCatchupUnavailableReason.INVALID_METADATA)
            M3uCatchupDialectSelection.Supported(M3uCatchupDialect.Append(template))
        }

        MODE_SHIFT,
        MODE_TIMESHIFT,
        -> {
            if (hasSource) {
                unavailableDialect(M3uCatchupUnavailableReason.INVALID_METADATA)
            } else {
                M3uCatchupDialectSelection.Supported(M3uCatchupDialect.Shift)
            }
        }

        MODE_FLUSSONIC -> {
            if (hasSource || intent !is PlaybackIntent.CatchupProgram) {
                unavailableDialect(M3uCatchupUnavailableReason.INVALID_METADATA)
            } else {
                M3uCatchupDialectSelection.Supported(M3uCatchupDialect.FlussonicHls)
            }
        }

        MODE_FLUSSONIC_TS -> {
            if (hasSource || intent !is PlaybackIntent.CatchupProgram) {
                unavailableDialect(M3uCatchupUnavailableReason.INVALID_METADATA)
            } else {
                M3uCatchupDialectSelection.Supported(M3uCatchupDialect.FlussonicTs)
            }
        }

        MODE_XC,
        MODE_XTREAM,
        -> {
            if (hasSource || intent !is PlaybackIntent.CatchupProgram) {
                unavailableDialect(M3uCatchupUnavailableReason.INVALID_METADATA)
            } else {
                M3uCatchupDialectSelection.Supported(M3uCatchupDialect.XtreamPhp)
            }
        }

        else -> unavailableDialect(M3uCatchupUnavailableReason.UNSUPPORTED_MODE)
    }
}

internal class M3uCatchupTemplate private constructor(
    private val parts: List<Part>,
) {
    fun materialize(context: M3uCatchupTemplateContext): String? {
        val output = StringBuilder()
        for (part in parts) {
            when (part) {
                is Part.Literal -> output.append(part.value)
                is Part.Token -> output.append(context.valueFor(part.kind) ?: return null)
            }
            if (output.length > MAX_MATERIALIZED_CHARACTERS) return null
        }
        return output.toString()
    }

    override fun toString(): String = "M3uCatchupTemplate(<redacted>)"

    private sealed interface Part {
        data class Literal(val value: String) : Part
        data class Token(val kind: TokenKind) : Part
    }

    internal enum class TokenKind {
        START_EPOCH_SECONDS,
        END_EPOCH_SECONDS,
        NOW_EPOCH_SECONDS,
        DURATION_SECONDS,
        OFFSET_SECONDS,
        YEAR,
        MONTH,
        DAY,
        HOUR,
        MINUTE,
        SECOND,
    }

    companion object {
        private val parseLimits = M3uParseLimits()
        private val MAX_TEMPLATE_CHARACTERS = parseLimits.maxAttributeCharactersPerRecord
        private val MAX_TOKENS = parseLimits.maxAttributesPerRecord
        private val MAX_MATERIALIZED_CHARACTERS = parseLimits.maxLineBytes

        fun compile(rawTemplate: String): M3uCatchupTemplate? {
            if (rawTemplate.isBlank() || rawTemplate.length > MAX_TEMPLATE_CHARACTERS) return null
            if (!rawTemplate.startsWith("?") && !rawTemplate.startsWith("&")) return null

            val parts = mutableListOf<Part>()
            val literal = StringBuilder()
            var tokenCount = 0
            var index = 0

            fun flushLiteral() {
                if (literal.isNotEmpty()) {
                    parts += Part.Literal(literal.toString())
                    literal.setLength(0)
                }
            }

            while (index < rawTemplate.length) {
                val char = rawTemplate[index]
                val tokenStart = when {
                    char == '$' && index + 1 < rawTemplate.length && rawTemplate[index + 1] == '{' ->
                        index + 1
                    char == '{' -> index
                    char == '}' -> return null
                    else -> null
                }

                if (tokenStart == null) {
                    literal.append(char)
                    index += 1
                    continue
                }

                flushLiteral()
                val closeIndex = rawTemplate.indexOf('}', startIndex = tokenStart + 1)
                if (closeIndex < 0) return null

                val tokenName = rawTemplate.substring(tokenStart + 1, closeIndex)
                val tokenKind = tokenName.toTokenKindOrNull() ?: return null
                tokenCount += 1
                if (tokenCount > MAX_TOKENS) return null

                parts += Part.Token(tokenKind)
                index = closeIndex + 1
            }

            flushLiteral()
            if (tokenCount == 0) return null
            return M3uCatchupTemplate(parts)
        }
    }
}

internal data class M3uCatchupTemplateContext(
    val transportStartEpochMillis: Long,
    val requestedPositionEpochMillis: Long,
    val transportEndEpochMillis: Long?,
    val nowEpochMillis: Long,
    val programmeDurationMillis: Long?,
) {
    fun valueFor(token: M3uCatchupTemplate.TokenKind): String? = when (token) {
        M3uCatchupTemplate.TokenKind.START_EPOCH_SECONDS ->
            Math.floorDiv(transportStartEpochMillis, SECOND_MILLIS).toString()

        M3uCatchupTemplate.TokenKind.END_EPOCH_SECONDS ->
            transportEndEpochMillis?.let { Math.floorDiv(it, SECOND_MILLIS).toString() }

        M3uCatchupTemplate.TokenKind.NOW_EPOCH_SECONDS ->
            Math.floorDiv(nowEpochMillis, SECOND_MILLIS).toString()

        M3uCatchupTemplate.TokenKind.DURATION_SECONDS ->
            programmeDurationMillis
                ?.takeIf { it > 0L }
                ?.let(::ceilSeconds)
                ?.toString()

        M3uCatchupTemplate.TokenKind.OFFSET_SECONDS -> runCatching {
            Math.subtractExact(nowEpochMillis, requestedPositionEpochMillis)
        }.getOrNull()
            ?.takeIf { it >= 0L }
            ?.let { Math.floorDiv(it, SECOND_MILLIS).toString() }

        M3uCatchupTemplate.TokenKind.YEAR -> utcStart().year.toString().padStart(4, '0')
        M3uCatchupTemplate.TokenKind.MONTH -> utcStart().monthValue.toString().padStart(2, '0')
        M3uCatchupTemplate.TokenKind.DAY -> utcStart().dayOfMonth.toString().padStart(2, '0')
        M3uCatchupTemplate.TokenKind.HOUR -> utcStart().hour.toString().padStart(2, '0')
        M3uCatchupTemplate.TokenKind.MINUTE -> utcStart().minute.toString().padStart(2, '0')
        M3uCatchupTemplate.TokenKind.SECOND -> utcStart().second.toString().padStart(2, '0')
    }

    private fun utcStart() =
        Instant.ofEpochMilli(transportStartEpochMillis).atOffset(ZoneOffset.UTC)
}

private fun String.toTokenKindOrNull(): M3uCatchupTemplate.TokenKind? {
    if (length == 1) {
        return when (this) {
            "Y" -> M3uCatchupTemplate.TokenKind.YEAR
            "m" -> M3uCatchupTemplate.TokenKind.MONTH
            "d" -> M3uCatchupTemplate.TokenKind.DAY
            "H" -> M3uCatchupTemplate.TokenKind.HOUR
            "M" -> M3uCatchupTemplate.TokenKind.MINUTE
            "S" -> M3uCatchupTemplate.TokenKind.SECOND
            else -> null
        }
    }

    return when (lowercase(Locale.ROOT)) {
        "start", "utc", "timestamp", "start-timestamp", "utcstart" ->
            M3uCatchupTemplate.TokenKind.START_EPOCH_SECONDS
        "end", "utcend", "end-timestamp", "stop" ->
            M3uCatchupTemplate.TokenKind.END_EPOCH_SECONDS
        "lutc", "now", "timenow", "currenttime" ->
            M3uCatchupTemplate.TokenKind.NOW_EPOCH_SECONDS
        "duration" -> M3uCatchupTemplate.TokenKind.DURATION_SECONDS
        "offset" -> M3uCatchupTemplate.TokenKind.OFFSET_SECONDS
        else -> null
    }
}

private fun ceilSeconds(milliseconds: Long): Long {
    val whole = Math.floorDiv(milliseconds, SECOND_MILLIS)
    return if (milliseconds % SECOND_MILLIS == 0L) whole else Math.addExact(whole, 1L)
}

private fun unavailableDialect(reason: M3uCatchupUnavailableReason) =
    M3uCatchupDialectSelection.Unavailable(reason)

internal const val SECOND_MILLIS = 1_000L
internal const val MINUTE_MILLIS = 60 * SECOND_MILLIS

private const val MODE_APPEND = "append"
private const val MODE_SHIFT = "shift"
private const val MODE_TIMESHIFT = "timeshift"
private const val MODE_FLUSSONIC = "flussonic"
private const val MODE_FLUSSONIC_TS = "flussonic-ts"
private const val MODE_XC = "xc"
private const val MODE_XTREAM = "xtream"

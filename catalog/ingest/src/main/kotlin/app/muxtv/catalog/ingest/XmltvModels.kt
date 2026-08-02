package app.muxtv.catalog.ingest

import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Collections

class XmltvParseLimits(
    val maxInputBytes: Long = 512L * 1024L * 1024L,
    val maxDepth: Int = 64,
    val maxElements: Long = 10_000_000L,
    val maxAttributesPerElement: Int = 64,
    val maxTextCharactersPerElement: Int = 256 * 1024,
    val maxChannels: Int = 100_000,
    val maxProgrammes: Int = 2_000_000,
    val maxDisplayNamesPerChannel: Int = 32,
    val maxIconsPerRecord: Int = 16,
    val maxCategoriesPerProgramme: Int = 64,
    val maxCreditsPerProgramme: Int = 128,
    val maxUrlsPerRecord: Int = 16,
    val maxKeywordsPerProgramme: Int = 64,
    val maxCountriesPerProgramme: Int = 16,
    val maxEpisodeNumbersPerProgramme: Int = 16,
    val maxStringCharacters: Int = maxTextCharactersPerElement,
) {
    init {
        require(maxInputBytes > 0L)
        require(maxDepth > 0)
        require(maxElements > 0L)
        require(maxAttributesPerElement > 0)
        require(maxTextCharactersPerElement > 0)
        require(maxChannels > 0)
        require(maxProgrammes > 0)
        require(maxDisplayNamesPerChannel > 0)
        require(maxIconsPerRecord > 0)
        require(maxCategoriesPerProgramme > 0)
        require(maxCreditsPerProgramme > 0)
        require(maxUrlsPerRecord > 0)
        require(maxKeywordsPerProgramme > 0)
        require(maxCountriesPerProgramme > 0)
        require(maxEpisodeNumbersPerProgramme > 0)
        require(maxStringCharacters > 0)
        require(maxStringCharacters <= maxTextCharactersPerElement)
    }
}

enum class XmltvTimestampPrecision { Year, Month, Day, Hour, Minute, Second }

sealed class XmltvTimestamp {
    abstract val precision: XmltvTimestampPrecision
    abstract val inferredComponents: Boolean

    class Resolved(
        val instant: Instant,
        val offset: ZoneOffset,
        override val precision: XmltvTimestampPrecision,
        override val inferredComponents: Boolean,
    ) : XmltvTimestamp() {
        override fun toString(): String =
            "XmltvTimestamp.Resolved(precision=$precision, inferredComponents=$inferredComponents)"
    }

    class Unresolved(
        val localDateTime: LocalDateTime,
        override val precision: XmltvTimestampPrecision,
        override val inferredComponents: Boolean,
    ) : XmltvTimestamp() {
        override fun toString(): String =
            "XmltvTimestamp.Unresolved(precision=$precision, inferredComponents=$inferredComponents)"
    }
}

class XmltvText(val value: String, val language: String?) {
    init { require(value.isNotEmpty()) }
    override fun toString(): String =
        "XmltvText(length=${value.length}, languagePresent=${language != null})"
}

class XmltvIcon(val source: String, val width: Int?, val height: Int?) {
    init {
        require(source.isNotEmpty())
        require(width == null || width > 0)
        require(height == null || height > 0)
    }
    override fun toString(): String =
        "XmltvIcon(sourcePresent=true, width=$width, height=$height)"
}

enum class XmltvCreditRole {
    Director, Actor, Writer, Adapter, Producer, Composer, Editor, Presenter, Commentator, Guest,
}

class XmltvCredit(val role: XmltvCreditRole, val name: String) {
    init { require(name.isNotEmpty()) }
    override fun toString(): String = "XmltvCredit(role=$role, nameLength=${name.length})"
}

class XmltvEpisodeNumber(val system: String?, val value: String) {
    init { require(value.isNotEmpty()) }
    override fun toString(): String =
        "XmltvEpisodeNumber(systemPresent=${system != null}, valueLength=${value.length})"
}

class XmltvChannel(
    val externalId: String,
    displayNames: List<XmltvText>,
    icons: List<XmltvIcon>,
    urls: List<String>,
) {
    val displayNames: List<XmltvText> = displayNames.immutableSnapshot()
    val icons: List<XmltvIcon> = icons.immutableSnapshot()
    val urls: List<String> = urls.immutableSnapshot()
    init { require(externalId.isNotEmpty()) }
    override fun toString(): String =
        "XmltvChannel(displayNameCount=${displayNames.size}, iconCount=${icons.size}, urlCount=${urls.size})"
}

class XmltvProgramme(
    val externalChannelId: String,
    val start: XmltvTimestamp,
    val stop: XmltvTimestamp?,
    val pdcStart: XmltvTimestamp?,
    val vpsStart: XmltvTimestamp?,
    titles: List<XmltvText>,
    subTitles: List<XmltvText>,
    descriptions: List<XmltvText>,
    categories: List<XmltvText>,
    keywords: List<String>,
    countries: List<String>,
    urls: List<String>,
    icons: List<XmltvIcon>,
    episodeNumbers: List<XmltvEpisodeNumber>,
    credits: List<XmltvCredit>,
    val previouslyShown: Boolean,
    val premiere: Boolean,
    val lastChance: Boolean,
    val isNew: Boolean,
) {
    val titles = titles.immutableSnapshot()
    val subTitles = subTitles.immutableSnapshot()
    val descriptions = descriptions.immutableSnapshot()
    val categories = categories.immutableSnapshot()
    val keywords = keywords.immutableSnapshot()
    val countries = countries.immutableSnapshot()
    val urls = urls.immutableSnapshot()
    val icons = icons.immutableSnapshot()
    val episodeNumbers = episodeNumbers.immutableSnapshot()
    val credits = credits.immutableSnapshot()
    init { require(externalChannelId.isNotEmpty()) }
    override fun toString(): String =
        "XmltvProgramme(start=$start, stopPresent=${stop != null}, titleCount=${titles.size}, " +
            "categoryCount=${categories.size}, iconCount=${icons.size}, creditCount=${credits.size}, " +
            "previouslyShown=$previouslyShown, premiere=$premiere, lastChance=$lastChance, isNew=$isNew)"
}

enum class XmltvWarningKind {
    MissingChannelId,
    MissingProgrammeChannel,
    MissingProgrammeStart,
    InvalidTimestamp,
    StopBeforeStart,
    CollectionLimitExceeded,
}

class XmltvWarning(val kind: XmltvWarningKind, val lineNumber: Int?, val columnNumber: Int?) {
    override fun toString(): String =
        "XmltvWarning(kind=$kind, lineNumber=$lineNumber, columnNumber=$columnNumber)"
}

enum class XmltvParseFailureReason {
    MalformedXml,
    ForbiddenDoctype,
    ForbiddenExternalEntity,
    SecureConfigurationUnavailable,
    InputReadFailed,
    InputBytesExceeded,
    DepthExceeded,
    ElementCountExceeded,
    AttributeCountExceeded,
    TextCharactersExceeded,
    ChannelCountExceeded,
    ProgrammeCountExceeded,
}

class XmltvParseException(
    val reason: XmltvParseFailureReason,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
) : IOException(
    buildString {
        append("XMLTV parse failed: reason=").append(reason)
        lineNumber?.let { append(", lineNumber=").append(it) }
        columnNumber?.let { append(", columnNumber=").append(it) }
    },
)

class XmltvParseReport(
    val channelCount: Int,
    val programmeCount: Int,
    val warningCount: Int,
    val elementCount: Long,
    val consumedBytes: Long,
) {
    init {
        require(channelCount >= 0)
        require(programmeCount >= 0)
        require(warningCount >= 0)
        require(elementCount >= 0L)
        require(consumedBytes >= 0L)
    }
    override fun toString(): String =
        "XmltvParseReport(channelCount=$channelCount, programmeCount=$programmeCount, " +
            "warningCount=$warningCount, elementCount=$elementCount, consumedBytes=$consumedBytes)"
}

interface XmltvParseSink {
    suspend fun onChannel(channel: XmltvChannel)
    suspend fun onProgramme(programme: XmltvProgramme)
    suspend fun onWarning(warning: XmltvWarning)
}

private fun <T> List<T>.immutableSnapshot(): List<T> = when (size) {
    0 -> Collections.emptyList()
    1 -> Collections.singletonList(this[0])
    else -> Collections.unmodifiableList(ArrayList(this))
}
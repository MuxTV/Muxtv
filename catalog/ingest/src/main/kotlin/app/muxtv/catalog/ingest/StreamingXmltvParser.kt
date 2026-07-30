package app.muxtv.catalog.ingest

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.ext.DefaultHandler2

class StreamingXmltvParser(
    private val parserDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun parse(
        input: InputStream,
        sink: XmltvParseSink,
        limits: XmltvParseLimits = XmltvParseLimits(),
    ): XmltvParseReport = coroutineScope {
        val events = Channel<XmltvEvent>(capacity = 1)
        val callerJob = currentCoroutineContext()[Job]
            ?: error("XMLTV parsing requires a coroutine Job")
        val guardedInput = GuardedXmltvInputStream(input, limits.maxInputBytes)
        val producer = async(parserDispatcher) {
            try {
                parseBlocking(guardedInput, limits, callerJob) { event ->
                    val result = events.trySendBlocking(event)
                    if (result.isFailure) {
                        throw result.exceptionOrNull()
                            ?: CancellationException("XMLTV event consumer is unavailable")
                    }
                }
            } finally {
                events.close()
            }
        }

        try {
            for (event in events) {
                currentCoroutineContext().ensureActive()
                when (event) {
                    is XmltvEvent.ChannelRecord -> sink.onChannel(event.value)
                    is XmltvEvent.ProgrammeRecord -> sink.onProgramme(event.value)
                    is XmltvEvent.WarningRecord -> sink.onWarning(event.value)
                }
                currentCoroutineContext().ensureActive()
            }
            producer.await()
        } catch (failure: Throwable) {
            events.cancel()
            producer.cancelAndJoin()
            throw failure
        }
    }

    private fun parseBlocking(
        input: GuardedXmltvInputStream,
        limits: XmltvParseLimits,
        job: Job,
        emit: (XmltvEvent) -> Unit,
    ): XmltvParseReport {
        val handler = XmltvHandler(limits, job, emit)
        val reader = createSecureReader(handler)
        try {
            reader.parse(InputSource(input))
        } catch (failure: Throwable) {
            if (failure is Error) throw failure

            failure.findCause<XmltvDoctypeInputException>()?.let {
                throw XmltvParseException(XmltvParseFailureReason.ForbiddenDoctype)
            }
            failure.findCause<XmltvSaxAbortException>()?.let { abort ->
                throw XmltvParseException(
                    reason = abort.reason,
                    lineNumber = abort.safeLineNumber,
                    columnNumber = abort.safeColumnNumber,
                )
            }
            failure.findCause<XmltvInputLimitException>()?.let {
                throw XmltvParseException(XmltvParseFailureReason.InputBytesExceeded)
            }
            failure.findCause<CancellationException>()?.let { throw it }

            when (failure) {
                is XmltvParseException -> throw failure
                is SAXParseException -> throw XmltvParseException(
                    reason = XmltvParseFailureReason.MalformedXml,
                    lineNumber = failure.lineNumber.takeIf { it > 0 },
                    columnNumber = failure.columnNumber.takeIf { it > 0 },
                )
                is SAXException -> throw XmltvParseException(XmltvParseFailureReason.MalformedXml)
                is IOException -> throw XmltvParseException(XmltvParseFailureReason.InputReadFailed)
                else -> throw failure
            }
        }
        return handler.report(input.consumedBytes)
    }

    private fun createSecureReader(handler: XmltvHandler): XMLReader {
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                try {
                    isXIncludeAware = false
                } catch (_: UnsupportedOperationException) {
                    // SAX parsing does not opt into XInclude; this remains defense in depth.
                }
            }
            factory.setRequiredFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setOptionalFeature(EXTERNAL_GENERAL_ENTITIES, false)
            factory.setOptionalFeature(EXTERNAL_PARAMETER_ENTITIES, false)
            factory.setOptionalFeature(LOAD_EXTERNAL_DTD, false)

            val parser = factory.newSAXParser()
            parser.setOptionalProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            parser.setOptionalProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")

            return parser.xmlReader.apply {
                setRequiredFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setOptionalFeature(EXTERNAL_GENERAL_ENTITIES, false)
                setOptionalFeature(EXTERNAL_PARAMETER_ENTITIES, false)
                setOptionalFeature(LOAD_EXTERNAL_DTD, false)
                try {
                    setProperty(LEXICAL_HANDLER, handler)
                } catch (failure: SAXException) {
                    throw XmltvSecureConfigurationException(failure)
                }
                contentHandler = handler
                errorHandler = handler
                entityResolver = handler
            }
        } catch (_: XmltvSecureConfigurationException) {
            throw XmltvParseException(XmltvParseFailureReason.SecureConfigurationUnavailable)
        } catch (_: ParserConfigurationException) {
            throw XmltvParseException(XmltvParseFailureReason.SecureConfigurationUnavailable)
        } catch (_: SAXException) {
            throw XmltvParseException(XmltvParseFailureReason.SecureConfigurationUnavailable)
        }
    }

    private companion object {
        const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
        const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"
    }
}

private class XmltvHandler(
    private val limits: XmltvParseLimits,
    private val job: Job,
    private val emit: (XmltvEvent) -> Unit,
) : DefaultHandler2() {
    private var locator: Locator? = null
    private var depth = 0
    private var elementCount = 0L
    private var encounteredChannels = 0
    private var encounteredProgrammes = 0
    private var emittedChannels = 0
    private var emittedProgrammes = 0
    private var warningCount = 0
    private var channel: ChannelBuilder? = null
    private var programme: ProgrammeBuilder? = null
    private var capture: TextCapture? = null
    private val textCharactersByDepth = IntArray(limits.maxDepth + 1)

    override fun setDocumentLocator(locator: Locator) {
        this.locator = locator
    }

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        job.ensureActive()
        depth += 1
        if (depth > limits.maxDepth) abort(XmltvParseFailureReason.DepthExceeded)
        elementCount += 1
        if (elementCount > limits.maxElements) abort(XmltvParseFailureReason.ElementCountExceeded)
        textCharactersByDepth[depth] = 0

        val name = elementName(localName, qName)
        validateElement(name, attributes)
        when (name) {
            "channel" -> startChannel(attributes)
            "programme" -> startProgramme(attributes)
            "icon" -> addIcon(attributes)
            "previously-shown" -> programme?.previouslyShown = true
            "premiere" -> programme?.premiere = true
            "last-chance" -> programme?.lastChance = true
            "new" -> programme?.isNew = true
            else -> startTextCapture(name, attributes)
        }
    }

    override fun characters(characters: CharArray, start: Int, length: Int) {
        job.ensureActive()
        for (openDepth in 1..depth) {
            val nextCount = textCharactersByDepth[openDepth] + length
            if (nextCount > limits.maxTextCharactersPerElement) {
                abort(XmltvParseFailureReason.TextCharactersExceeded)
            }
            textCharactersByDepth[openDepth] = nextCount
        }
        capture?.text?.append(characters, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        job.ensureActive()
        val name = elementName(localName, qName)
        if (capture?.elementName == name) {
            finishTextCapture(capture!!)
            capture = null
        }
        when (name) {
            "channel" -> finishChannel()
            "programme" -> finishProgramme()
        }
        textCharactersByDepth[depth] = 0
        depth -= 1
    }

    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        abort(XmltvParseFailureReason.ForbiddenDoctype)
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        throw XmltvSaxAbortException(
            XmltvParseFailureReason.ForbiddenExternalEntity,
            safeLine(),
            safeColumn(),
        )

    override fun resolveEntity(
        name: String?,
        publicId: String?,
        baseURI: String?,
        systemId: String?,
    ): InputSource = resolveEntity(publicId, systemId)

    override fun error(exception: SAXParseException) {
        throw exception
    }

    override fun fatalError(exception: SAXParseException) {
        throw exception
    }

    fun report(consumedBytes: Long): XmltvParseReport = XmltvParseReport(
        channelCount = emittedChannels,
        programmeCount = emittedProgrammes,
        warningCount = warningCount,
        elementCount = elementCount,
        consumedBytes = consumedBytes,
    )

    private fun startChannel(attributes: Attributes) {
        encounteredChannels += 1
        if (encounteredChannels > limits.maxChannels) abort(XmltvParseFailureReason.ChannelCountExceeded)
        channel = ChannelBuilder(
            externalId = attributes.safeValue("id"),
            lineNumber = safeLine(),
            columnNumber = safeColumn(),
        )
        programme = null
        capture = null
    }

    private fun startProgramme(attributes: Attributes) {
        encounteredProgrammes += 1
        if (encounteredProgrammes > limits.maxProgrammes) abort(XmltvParseFailureReason.ProgrammeCountExceeded)
        programme = ProgrammeBuilder(
            externalChannelId = attributes.safeValue("channel"),
            startRaw = attributes.safeValue("start"),
            stopRaw = attributes.safeValue("stop"),
            pdcStartRaw = attributes.safeValue("pdc-start"),
            vpsStartRaw = attributes.safeValue("vps-start"),
            lineNumber = safeLine(),
            columnNumber = safeColumn(),
        )
        channel = null
        capture = null
    }

    private fun addIcon(attributes: Attributes) {
        val source = attributes.safeValue("src")?.trim().orEmpty()
        if (source.isEmpty()) return
        val icon = XmltvIcon(
            source = source,
            width = attributes.safePositiveInt("width"),
            height = attributes.safePositiveInt("height"),
        )
        channel?.let { addBounded(it.icons, icon, limits.maxIconsPerRecord) }
        programme?.let { addBounded(it.icons, icon, limits.maxIconsPerRecord) }
    }

    private fun startTextCapture(name: String, attributes: Attributes) {
        val creditRole = name.toCreditRole()
        val recognized = when {
            channel != null -> name == "display-name" || name == "url"
            programme != null -> name in PROGRAMME_TEXT_ELEMENTS || creditRole != null
            else -> false
        }
        if (!recognized || capture != null) return
        capture = TextCapture(
            elementName = name,
            language = attributes.safeValue("lang"),
            system = attributes.safeValue("system"),
            creditRole = creditRole,
        )
    }

    private fun finishTextCapture(active: TextCapture) {
        val value = active.text.toString().trim()
        if (value.isEmpty()) return
        if (value.length > limits.maxStringCharacters) abort(XmltvParseFailureReason.TextCharactersExceeded)

        channel?.let { builder ->
            when (active.elementName) {
                "display-name" -> addBounded(
                    builder.displayNames,
                    XmltvText(value, active.language.normalizedOptional()),
                    limits.maxDisplayNamesPerChannel,
                )
                "url" -> addBounded(builder.urls, value, limits.maxUrlsPerRecord)
            }
        }
        programme?.let { builder ->
            when (active.elementName) {
                "title" -> addBounded(builder.titles, active.asText(), limits.maxCategoriesPerProgramme)
                "sub-title" -> addBounded(builder.subTitles, active.asText(), limits.maxCategoriesPerProgramme)
                "desc" -> addBounded(builder.descriptions, active.asText(), limits.maxCategoriesPerProgramme)
                "category" -> addBounded(builder.categories, active.asText(), limits.maxCategoriesPerProgramme)
                "keyword" -> addBounded(builder.keywords, value, limits.maxKeywordsPerProgramme)
                "country" -> addBounded(builder.countries, value, limits.maxCountriesPerProgramme)
                "url" -> addBounded(builder.urls, value, limits.maxUrlsPerRecord)
                "episode-num" -> addBounded(
                    builder.episodeNumbers,
                    XmltvEpisodeNumber(active.system.normalizedOptional(), value),
                    limits.maxEpisodeNumbersPerProgramme,
                )
                else -> active.creditRole?.let { role ->
                    addBounded(builder.credits, XmltvCredit(role, value), limits.maxCreditsPerProgramme)
                }
            }
        }
    }

    private fun TextCapture.asText(): XmltvText =
        XmltvText(text.toString().trim(), language.normalizedOptional())

    private fun finishChannel() {
        val builder = channel ?: return
        channel = null
        capture = null
        val externalId = builder.externalId?.trim().orEmpty()
        if (externalId.isEmpty()) {
            warn(XmltvWarningKind.MissingChannelId, builder.lineNumber, builder.columnNumber)
            return
        }
        emit(
            XmltvEvent.ChannelRecord(
                XmltvChannel(externalId, builder.displayNames, builder.icons, builder.urls),
            ),
        )
        emittedChannels += 1
    }

    private fun finishProgramme() {
        val builder = programme ?: return
        programme = null
        capture = null
        val channelId = builder.externalChannelId?.trim().orEmpty()
        if (channelId.isEmpty()) {
            warn(XmltvWarningKind.MissingProgrammeChannel, builder.lineNumber, builder.columnNumber)
            return
        }
        val startRaw = builder.startRaw?.trim().orEmpty()
        if (startRaw.isEmpty()) {
            warn(XmltvWarningKind.MissingProgrammeStart, builder.lineNumber, builder.columnNumber)
            return
        }
        val start = XmltvTimestampParser.parse(startRaw)
        if (start == null) {
            warn(XmltvWarningKind.InvalidTimestamp, builder.lineNumber, builder.columnNumber)
            return
        }
        val stop = parseOptionalTimestamp(builder.stopRaw, builder)
            ?: if (builder.stopRaw.normalizedOptional() != null) return else null
        val pdcStart = parseOptionalTimestamp(builder.pdcStartRaw, builder)
            ?: if (builder.pdcStartRaw.normalizedOptional() != null) return else null
        val vpsStart = parseOptionalTimestamp(builder.vpsStartRaw, builder)
            ?: if (builder.vpsStartRaw.normalizedOptional() != null) return else null
        if (stop != null && stop.isBefore(start)) {
            warn(XmltvWarningKind.StopBeforeStart, builder.lineNumber, builder.columnNumber)
            return
        }
        emit(
            XmltvEvent.ProgrammeRecord(
                XmltvProgramme(
                    externalChannelId = channelId,
                    start = start,
                    stop = stop,
                    pdcStart = pdcStart,
                    vpsStart = vpsStart,
                    titles = builder.titles,
                    subTitles = builder.subTitles,
                    descriptions = builder.descriptions,
                    categories = builder.categories,
                    keywords = builder.keywords,
                    countries = builder.countries,
                    urls = builder.urls,
                    icons = builder.icons,
                    episodeNumbers = builder.episodeNumbers,
                    credits = builder.credits,
                    previouslyShown = builder.previouslyShown,
                    premiere = builder.premiere,
                    lastChance = builder.lastChance,
                    isNew = builder.isNew,
                ),
            ),
        )
        emittedProgrammes += 1
    }

    private fun parseOptionalTimestamp(raw: String?, builder: ProgrammeBuilder): XmltvTimestamp? {
        val normalized = raw.normalizedOptional() ?: return null
        return XmltvTimestampParser.parse(normalized).also { parsed ->
            if (parsed == null) {
                warn(XmltvWarningKind.InvalidTimestamp, builder.lineNumber, builder.columnNumber)
            }
        }
    }

    private fun <T> addBounded(target: MutableList<T>, value: T, maximum: Int) {
        if (target.size < maximum) {
            target += value
        } else {
            warn(XmltvWarningKind.CollectionLimitExceeded, safeLine(), safeColumn())
        }
    }

    private fun warn(kind: XmltvWarningKind, lineNumber: Int?, columnNumber: Int?) {
        warningCount += 1
        emit(XmltvEvent.WarningRecord(XmltvWarning(kind, lineNumber, columnNumber)))
    }

    private fun abort(reason: XmltvParseFailureReason): Nothing =
        throw XmltvSaxAbortException(reason, safeLine(), safeColumn())

    private fun safeLine(): Int? = locator?.lineNumber?.takeIf { it > 0 }
    private fun safeColumn(): Int? = locator?.columnNumber?.takeIf { it > 0 }

    private fun validateElement(name: String, attributes: Attributes) {
        if (name.length > limits.maxStringCharacters) {
            abort(XmltvParseFailureReason.TextCharactersExceeded)
        }
        if (attributes.length > limits.maxAttributesPerElement) {
            abort(XmltvParseFailureReason.AttributeCountExceeded)
        }
        for (index in 0 until attributes.length) {
            val attributeName = elementName(attributes.getLocalName(index), attributes.getQName(index))
            val attributeValue = attributes.getValue(index).orEmpty()
            if (
                attributeName.length > limits.maxStringCharacters ||
                attributeValue.length > limits.maxStringCharacters
            ) {
                abort(XmltvParseFailureReason.TextCharactersExceeded)
            }
        }
    }

    private fun Attributes.safeValue(name: String): String? {
        val value = getValue(name) ?: return null
        if (value.length > limits.maxStringCharacters) abort(XmltvParseFailureReason.TextCharactersExceeded)
        return value
    }

    private fun Attributes.safePositiveInt(name: String): Int? =
        safeValue(name)?.toIntOrNull()?.takeIf { it > 0 }

    private companion object {
        val PROGRAMME_TEXT_ELEMENTS = setOf(
            "title", "sub-title", "desc", "category", "keyword", "country", "url", "episode-num",
        )
    }
}

private sealed interface XmltvEvent {
    class ChannelRecord(val value: XmltvChannel) : XmltvEvent
    class ProgrammeRecord(val value: XmltvProgramme) : XmltvEvent
    class WarningRecord(val value: XmltvWarning) : XmltvEvent
}

private class ChannelBuilder(
    val externalId: String?,
    val lineNumber: Int?,
    val columnNumber: Int?,
) {
    val displayNames = mutableListOf<XmltvText>()
    val icons = mutableListOf<XmltvIcon>()
    val urls = mutableListOf<String>()
}

private class ProgrammeBuilder(
    val externalChannelId: String?,
    val startRaw: String?,
    val stopRaw: String?,
    val pdcStartRaw: String?,
    val vpsStartRaw: String?,
    val lineNumber: Int?,
    val columnNumber: Int?,
) {
    val titles = mutableListOf<XmltvText>()
    val subTitles = mutableListOf<XmltvText>()
    val descriptions = mutableListOf<XmltvText>()
    val categories = mutableListOf<XmltvText>()
    val keywords = mutableListOf<String>()
    val countries = mutableListOf<String>()
    val urls = mutableListOf<String>()
    val icons = mutableListOf<XmltvIcon>()
    val episodeNumbers = mutableListOf<XmltvEpisodeNumber>()
    val credits = mutableListOf<XmltvCredit>()
    var previouslyShown = false
    var premiere = false
    var lastChance = false
    var isNew = false
}

private class TextCapture(
    val elementName: String,
    val language: String?,
    val system: String?,
    val creditRole: XmltvCreditRole?,
) {
    val text = StringBuilder()
}

private class XmltvSaxAbortException(
    val reason: XmltvParseFailureReason,
    val safeLineNumber: Int?,
    val safeColumnNumber: Int?,
) : SAXException("XMLTV parsing aborted")

private class XmltvInputLimitException : IOException("XMLTV input byte limit exceeded")
private class XmltvDoctypeInputException : IOException("XMLTV DOCTYPE is forbidden")
private class XmltvSecureConfigurationException(cause: Throwable) : Exception(cause)

private class GuardedXmltvInputStream(
    input: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(input) {
    var consumedBytes: Long = 0L
        private set
    private var doctypeMarkerIndex = 0

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            recordBytes(1)
            inspectByte(value)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            recordBytes(read)
            for (index in offset until offset + read) {
                inspectByte(buffer[index].toInt() and 0xff)
            }
        }
        return read
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0L) return 0L
        val buffer = ByteArray(minOf(byteCount, 8192L).toInt())
        var remaining = byteCount
        var skipped = 0L
        while (remaining > 0L) {
            val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            skipped += read
            remaining -= read
        }
        return skipped
    }

    override fun close() = Unit

    private fun recordBytes(count: Int) {
        consumedBytes += count
        if (consumedBytes > maximumBytes) throw XmltvInputLimitException()
    }

    private fun inspectByte(value: Int) {
        val normalized = value.asciiUppercase()
        val expected = DOCTYPE_MARKER[doctypeMarkerIndex]
        if (normalized == expected) {
            doctypeMarkerIndex += 1
            if (doctypeMarkerIndex == DOCTYPE_MARKER.size) throw XmltvDoctypeInputException()
            return
        }
        doctypeMarkerIndex = if (normalized == DOCTYPE_MARKER[0]) 1 else 0
    }

    private companion object {
        val DOCTYPE_MARKER = "<!DOCTYPE".encodeToByteArray().map { it.toInt() and 0xff }.toIntArray()
    }
}

private fun Int.asciiUppercase(): Int = if (this in 'a'.code..'z'.code) this - 32 else this

private fun SAXParserFactory.setRequiredFeature(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (failure: Exception) {
        throw XmltvSecureConfigurationException(failure)
    }
}

private fun SAXParserFactory.setOptionalFeature(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: ParserConfigurationException) {
        // Mandatory secure processing, resolver, lexical handler, and byte-level DOCTYPE guard remain active.
    } catch (_: SAXNotRecognizedException) {
        // Implementation-specific defense in depth.
    } catch (_: SAXNotSupportedException) {
        // Implementation-specific defense in depth.
    }
}

private fun SAXParser.setOptionalProperty(name: String, value: String) {
    try {
        setProperty(name, value)
    } catch (_: SAXNotRecognizedException) {
        // DOCTYPE rejection and the entity resolver still deny external access.
    } catch (_: SAXNotSupportedException) {
        // DOCTYPE rejection and the entity resolver still deny external access.
    }
}

private fun XMLReader.setRequiredFeature(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (failure: SAXException) {
        throw XmltvSecureConfigurationException(failure)
    }
}

private fun XMLReader.setOptionalFeature(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: SAXNotRecognizedException) {
        // Implementation-specific defense in depth.
    } catch (_: SAXNotSupportedException) {
        // Implementation-specific defense in depth.
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        val next = current.cause
        if (next === current) return null
        current = next
    }
    return null
}

private fun elementName(localName: String?, qName: String?): String =
    localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty()

private fun String?.normalizedOptional(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String.toCreditRole(): XmltvCreditRole? = when (this) {
    "director" -> XmltvCreditRole.Director
    "actor" -> XmltvCreditRole.Actor
    "writer" -> XmltvCreditRole.Writer
    "adapter" -> XmltvCreditRole.Adapter
    "producer" -> XmltvCreditRole.Producer
    "composer" -> XmltvCreditRole.Composer
    "editor" -> XmltvCreditRole.Editor
    "presenter" -> XmltvCreditRole.Presenter
    "commentator" -> XmltvCreditRole.Commentator
    "guest" -> XmltvCreditRole.Guest
    else -> null
}

private fun XmltvTimestamp.isBefore(other: XmltvTimestamp): Boolean = when {
    this is XmltvTimestamp.Resolved && other is XmltvTimestamp.Resolved -> instant < other.instant
    this is XmltvTimestamp.Unresolved && other is XmltvTimestamp.Unresolved -> localDateTime < other.localDateTime
    else -> false
}

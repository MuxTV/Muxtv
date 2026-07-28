package app.muxtv.testing.iptv

import java.net.URI

/** Payload family owned by one bounded starter fixture. */
enum class IptvStarterFixtureKind {
    HLS_MASTER,
    HLS_MEDIA,
    XMLTV,
}

/** Stable issue codes expected from future fixture consumers. */
enum class IptvStarterFixtureIssue {
    MISSING_VARIANT_URI,
    MISSING_XMLTV_CHANNEL,
    INVALID_XMLTV_TIMESTAMP,
}

sealed interface IptvStarterFixtureExpectation {
    val expectedIssues: Set<IptvStarterFixtureIssue>

    data class Hls(
        val variantCount: Int,
        val segmentCount: Int,
        val relativeResourceCount: Int,
        val absoluteResourceCount: Int,
        val encrypted: Boolean,
        override val expectedIssues: Set<IptvStarterFixtureIssue>,
    ) : IptvStarterFixtureExpectation {
        init {
            require(variantCount >= 0)
            require(segmentCount >= 0)
            require(relativeResourceCount >= 0)
            require(absoluteResourceCount >= 0)
            require(expectedIssues.all { it == IptvStarterFixtureIssue.MISSING_VARIANT_URI })
        }
    }

    data class XmlTv(
        val channelCount: Int,
        val programmeCount: Int,
        val unicode: Boolean,
        val timezoneOffsets: Set<String>,
        val missingChannelReferences: Int,
        val invalidTimestampCount: Int,
        override val expectedIssues: Set<IptvStarterFixtureIssue>,
    ) : IptvStarterFixtureExpectation {
        init {
            require(channelCount >= 0)
            require(programmeCount >= 0)
            require(missingChannelReferences >= 0)
            require(invalidTimestampCount >= 0)
            require(timezoneOffsets.all(TIMEZONE_OFFSET_PATTERN::matches))
            require(
                expectedIssues.all {
                    it == IptvStarterFixtureIssue.MISSING_XMLTV_CHANNEL ||
                        it == IptvStarterFixtureIssue.INVALID_XMLTV_TIMESTAMP
                },
            )
        }
    }
}

data class IptvStarterFixtureRedirect(
    val from: String,
    val to: String,
    val expectedAllowed: Boolean,
) {
    init {
        requireSyntheticAbsoluteResource(from)
        requireSyntheticAbsoluteResource(to)
        require(from != to)
    }
}

data class IptvStarterFixtureTransport(
    val requiredHeaderNames: Set<String> = emptySet(),
    val redirects: List<IptvStarterFixtureRedirect> = emptyList(),
) {
    init {
        require(requiredHeaderNames.all(SAFE_HEADER_NAMES::contains))
    }
}

class IptvStarterFixture(
    val id: String,
    val kind: IptvStarterFixtureKind,
    val text: String,
    val expectation: IptvStarterFixtureExpectation,
    absoluteResources: Set<String> = emptySet(),
    val transport: IptvStarterFixtureTransport = IptvStarterFixtureTransport(),
) {
    private val payloadBytes = text.toByteArray(Charsets.UTF_8)

    val utf8Bytes: ByteArray
        get() = payloadBytes.copyOf()

    val absoluteResources: Set<String> = absoluteResources.toSet()

    init {
        require(id.matches(FIXTURE_ID_PATTERN))
        require(text.endsWith('\n'))
        require('\r' !in text)
        require(payloadBytes.isNotEmpty())
        require(payloadBytes.size <= IptvStarterFixtureCatalog.MAX_FIXTURE_BYTES)
        this.absoluteResources.forEach(::requireSyntheticAbsoluteResource)
        when (kind) {
            IptvStarterFixtureKind.HLS_MASTER,
            IptvStarterFixtureKind.HLS_MEDIA,
            -> require(expectation is IptvStarterFixtureExpectation.Hls)

            IptvStarterFixtureKind.XMLTV ->
                require(expectation is IptvStarterFixtureExpectation.XmlTv)
        }
    }

    override fun toString(): String =
        "IptvStarterFixture(" +
            "id=$id, " +
            "kind=$kind, " +
            "utf8ByteCount=${payloadBytes.size}, " +
            "absoluteResourceCount=${absoluteResources.size}, " +
            "redirectCount=${transport.redirects.size}, " +
            "expectedIssueCount=${expectation.expectedIssues.size}" +
            ")"
}

/** Small provider-neutral fixtures shared by future HLS/XMLTV consumers and measurements. */
object IptvStarterFixtureCatalog {
    const val MAX_FIXTURE_BYTES: Int = 16 * 1024
    const val MAX_TOTAL_BYTES: Int = 64 * 1024

    val all: List<IptvStarterFixture> = listOf(
        hlsMasterRelative(),
        hlsMediaRelativeKey(),
        hlsMalformedMaster(),
        xmlTvDstUnicode(),
        xmlTvMissingChannelReference(),
        xmlTvMalformedTimestamp(),
    )

    private val byId = all.associateBy(IptvStarterFixture::id)

    init {
        require(byId.size == all.size)
        require(all.sumOf { it.utf8Bytes.size } <= MAX_TOTAL_BYTES)
    }

    fun require(id: String): IptvStarterFixture =
        byId[id] ?: throw IllegalArgumentException("Unknown IPTV starter fixture.")

    private fun hlsMasterRelative() = IptvStarterFixture(
        id = "hls-master-relative",
        kind = IptvStarterFixtureKind.HLS_MASTER,
        text = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,AVERAGE-BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            variants/720p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,AVERAGE-BANDWIDTH=3600000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            variants/1080p/index.m3u8
        """.trimIndent() + "\n",
        expectation = IptvStarterFixtureExpectation.Hls(
            variantCount = 2,
            segmentCount = 0,
            relativeResourceCount = 2,
            absoluteResourceCount = 0,
            encrypted = false,
            expectedIssues = emptySet(),
        ),
        absoluteResources = setOf(
            "https://origin.example/hls/master.m3u8",
            "https://edge.example/hls/master.m3u8",
        ),
        transport = IptvStarterFixtureTransport(
            requiredHeaderNames = setOf("Referer", "User-Agent"),
            redirects = listOf(
                IptvStarterFixtureRedirect(
                    from = "https://origin.example/hls/master.m3u8",
                    to = "https://edge.example/hls/master.m3u8",
                    expectedAllowed = true,
                ),
            ),
        ),
    )

    private fun hlsMediaRelativeKey() = IptvStarterFixture(
        id = "hls-media-relative-key",
        kind = IptvStarterFixtureKind.HLS_MEDIA,
        text = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:120
            #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin",IV=0x00000000000000000000000000000078
            #EXTINF:6.000,
            segments/00120.ts
            #EXTINF:6.000,
            segments/00121.ts
            #EXTINF:4.000,
            segments/00122.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n",
        expectation = IptvStarterFixtureExpectation.Hls(
            variantCount = 0,
            segmentCount = 3,
            relativeResourceCount = 4,
            absoluteResourceCount = 0,
            encrypted = true,
            expectedIssues = emptySet(),
        ),
        absoluteResources = setOf("https://media.example/hls/channel/index.m3u8"),
    )

    private fun hlsMalformedMaster() = IptvStarterFixture(
        id = "hls-malformed-master",
        kind = IptvStarterFixtureKind.HLS_MASTER,
        text = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480
        """.trimIndent() + "\n",
        expectation = IptvStarterFixtureExpectation.Hls(
            variantCount = 1,
            segmentCount = 0,
            relativeResourceCount = 0,
            absoluteResourceCount = 0,
            encrypted = false,
            expectedIssues = setOf(IptvStarterFixtureIssue.MISSING_VARIANT_URI),
        ),
        absoluteResources = setOf("https://broken.example/hls/master.m3u8"),
    )

    private fun xmlTvDstUnicode() = IptvStarterFixture(
        id = "xmltv-dst-unicode",
        kind = IptvStarterFixtureKind.XMLTV,
        text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="MuxTV Synthetic Corpus">
              <channel id="channel.one">
                <display-name lang="ru">Первый — 東京</display-name>
              </channel>
              <programme start="20261025013000 +0200" stop="20261025023000 +0200" channel="channel.one">
                <title lang="ru">До перехода</title>
              </programme>
              <programme start="20261025023000 +0100" stop="20261025033000 +0100" channel="channel.one">
                <title lang="ru">После перехода</title>
              </programme>
            </tv>
        """.trimIndent() + "\n",
        expectation = IptvStarterFixtureExpectation.XmlTv(
            channelCount = 1,
            programmeCount = 2,
            unicode = true,
            timezoneOffsets = setOf("+0200", "+0100"),
            missingChannelReferences = 0,
            invalidTimestampCount = 0,
            expectedIssues = emptySet(),
        ),
        absoluteResources = setOf("https://epg.example/xmltv/dst.xml"),
    )

    private fun xmlTvMissingChannelReference() = IptvStarterFixture(
        id = "xmltv-missing-channel-reference",
        kind = IptvStarterFixtureKind.XMLTV,
        text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="MuxTV Synthetic Corpus">
              <channel id="known.channel">
                <display-name lang="ru">Известный канал</display-name>
              </channel>
              <programme start="20260728120000 +0000" stop="20260728123000 +0000" channel="missing.channel">
                <title lang="ru">Передача без ссылки — тест</title>
              </programme>
            </tv>
        """.trimIndent() + "\n",
        expectation = IptvStarterFixtureExpectation.XmlTv(
            channelCount = 1,
            programmeCount = 1,
            unicode = true,
            timezoneOffsets = setOf("+0000"),
            missingChannelReferences = 1,
            invalidTimestampCount = 0,
            expectedIssues = setOf(IptvStarterFixtureIssue.MISSING_XMLTV_CHANNEL),
        ),
        absoluteResources = setOf("https://epg.example/xmltv/missing-reference.xml"),
    )

    private fun xmlTvMalformedTimestamp() = IptvStarterFixture(
        id = "xmltv-malformed-timestamp",
        kind = IptvStarterFixtureKind.XMLTV,
        text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv generator-info-name="MuxTV Synthetic Corpus">
              <channel id="channel.invalid-time">
                <display-name>Invalid Time</display-name>
              </channel>
              <programme start="not-a-timestamp" stop="20260728123000 +0000" channel="channel.invalid-time">
                <title>Malformed timestamp</title>
              </programme>
            </tv>
        """.trimIndent() + "\n",
        expectation = IptvStarterFixtureExpectation.XmlTv(
            channelCount = 1,
            programmeCount = 1,
            unicode = false,
            timezoneOffsets = emptySet(),
            missingChannelReferences = 0,
            invalidTimestampCount = 1,
            expectedIssues = setOf(IptvStarterFixtureIssue.INVALID_XMLTV_TIMESTAMP),
        ),
        absoluteResources = setOf("https://epg.example/xmltv/invalid-time.xml"),
    )
}

private val FIXTURE_ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private val TIMEZONE_OFFSET_PATTERN = Regex("[+-][0-9]{4}")
private val SAFE_HEADER_NAMES = setOf("Referer", "User-Agent")

private fun requireSyntheticAbsoluteResource(value: String) {
    val uri = URI(value)
    require(uri.isAbsolute)
    require(uri.scheme == "https")
    require(uri.userInfo == null)
    require(uri.query == null)
    require(uri.fragment == null)
    require(uri.host?.endsWith(".example") == true)
    require(uri.path?.startsWith('/') == true)
}

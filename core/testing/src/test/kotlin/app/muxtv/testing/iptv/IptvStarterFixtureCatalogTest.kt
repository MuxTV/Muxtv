package app.muxtv.testing.iptv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IptvStarterFixtureCatalogTest {
    @Test
    fun `catalog exposes stable unique bounded fixtures`() {
        val fixtures = IptvStarterFixtureCatalog.all

        assertThat(fixtures.map(IptvStarterFixture::id)).containsExactly(
            "hls-master-relative",
            "hls-media-relative-key",
            "hls-malformed-master",
            "xmltv-dst-unicode",
            "xmltv-missing-channel-reference",
            "xmltv-malformed-timestamp",
        ).inOrder()
        assertThat(fixtures.map(IptvStarterFixture::id).distinct()).hasSize(fixtures.size)
        assertThat(fixtures).isNotEmpty()
        fixtures.forEach { fixture ->
            assertThat(fixture.utf8Bytes.size).isGreaterThan(0)
            assertThat(fixture.utf8Bytes.size).isAtMost(IptvStarterFixtureCatalog.MAX_FIXTURE_BYTES)
            assertThat(fixture.text).endsWith("\n")
            assertThat(fixture.text).doesNotContain("\r")
            assertThat(fixture.toString()).doesNotContain(fixture.text)
        }
        assertThat(fixtures.sumOf { it.utf8Bytes.size })
            .isAtMost(IptvStarterFixtureCatalog.MAX_TOTAL_BYTES)
    }

    @Test
    fun `all fixture resources remain synthetic and secret free`() {
        IptvStarterFixtureCatalog.all.forEach { fixture ->
            val text = fixture.text.lowercase()

            assertThat(text).doesNotContain("authorization")
            assertThat(text).doesNotContain("cookie")
            assertThat(text).doesNotContain("token=")
            assertThat(text).doesNotContain("password")
            assertThat(text).doesNotContain("localhost")
            assertThat(text).doesNotContain("127.0.0.1")
            fixture.absoluteResources.forEach { resource ->
                assertThat(resource).contains(".example/")
                assertThat(resource).doesNotContain("?")
                assertThat(resource).doesNotContain("#")
            }
        }
    }

    @Test
    fun `HLS fixtures declare typed resource and issue expectations`() {
        val master = IptvStarterFixtureCatalog.require("hls-master-relative")
        val media = IptvStarterFixtureCatalog.require("hls-media-relative-key")
        val malformed = IptvStarterFixtureCatalog.require("hls-malformed-master")

        assertThat(master.kind).isEqualTo(IptvStarterFixtureKind.HLS_MASTER)
        assertThat(master.expectation).isEqualTo(
            IptvStarterFixtureExpectation.Hls(
                variantCount = 2,
                segmentCount = 0,
                relativeResourceCount = 2,
                absoluteResourceCount = 0,
                encrypted = false,
                expectedIssues = emptySet(),
            ),
        )
        assertThat(media.kind).isEqualTo(IptvStarterFixtureKind.HLS_MEDIA)
        assertThat(media.expectation).isEqualTo(
            IptvStarterFixtureExpectation.Hls(
                variantCount = 0,
                segmentCount = 3,
                relativeResourceCount = 4,
                absoluteResourceCount = 0,
                encrypted = true,
                expectedIssues = emptySet(),
            ),
        )
        assertThat(malformed.expectation).isEqualTo(
            IptvStarterFixtureExpectation.Hls(
                variantCount = 1,
                segmentCount = 0,
                relativeResourceCount = 0,
                absoluteResourceCount = 0,
                encrypted = false,
                expectedIssues = setOf(IptvStarterFixtureIssue.MISSING_VARIANT_URI),
            ),
        )
    }

    @Test
    fun `XMLTV fixtures declare timezone unicode and malformed contracts`() {
        val dst = IptvStarterFixtureCatalog.require("xmltv-dst-unicode")
        val missingReference = IptvStarterFixtureCatalog.require("xmltv-missing-channel-reference")
        val malformedTimestamp = IptvStarterFixtureCatalog.require("xmltv-malformed-timestamp")

        assertThat(dst.kind).isEqualTo(IptvStarterFixtureKind.XMLTV)
        assertThat(dst.expectation).isEqualTo(
            IptvStarterFixtureExpectation.XmlTv(
                channelCount = 1,
                programmeCount = 2,
                unicode = true,
                timezoneOffsets = setOf("+0200", "+0100"),
                missingChannelReferences = 0,
                invalidTimestampCount = 0,
                expectedIssues = emptySet(),
            ),
        )
        assertThat(missingReference.expectation).isEqualTo(
            IptvStarterFixtureExpectation.XmlTv(
                channelCount = 1,
                programmeCount = 1,
                unicode = true,
                timezoneOffsets = setOf("+0000"),
                missingChannelReferences = 1,
                invalidTimestampCount = 0,
                expectedIssues = setOf(IptvStarterFixtureIssue.MISSING_XMLTV_CHANNEL),
            ),
        )
        assertThat(malformedTimestamp.expectation).isEqualTo(
            IptvStarterFixtureExpectation.XmlTv(
                channelCount = 1,
                programmeCount = 1,
                unicode = false,
                timezoneOffsets = emptySet(),
                missingChannelReferences = 0,
                invalidTimestampCount = 1,
                expectedIssues = setOf(IptvStarterFixtureIssue.INVALID_XMLTV_TIMESTAMP),
            ),
        )
    }

    @Test
    fun `lookup rejects unknown fixture without echoing identifier`() {
        val unknown = "https://secret.example/fixture?token=do-not-print"

        val error = runCatching { IptvStarterFixtureCatalog.require(unknown) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error?.message).doesNotContain(unknown)
        assertThat(error?.message).isEqualTo("Unknown IPTV starter fixture.")
    }
}

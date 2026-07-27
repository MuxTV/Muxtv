package app.muxtv.catalog.refresh

import app.muxtv.network.ExactHttpOrigin
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteSourceAccessCodecTest {
    @Test
    fun `v2 round trips access descriptor and approved origins without exposing secrets`() {
        val access = RemoteSourceAccess(
            url = "http://provider.example:8080/list.m3u?token=secret-token",
            insecureHttpApproved = true,
            userAgent = "Provider Agent",
            referrer = "http://portal.example/",
            sensitiveHeaders = mapOf(
                "authorization" to "Bearer private-value",
                "x-api-key" to "private-api-key",
            ),
            approvedPlaybackOrigins = setOf(
                origin("http://provider.example:8080"),
                origin("http://cdn.example:80"),
                origin("http://video.example:8080"),
            ),
        )

        val decoded = RemoteSourceAccessCodec.encode(access).use(RemoteSourceAccessCodec::decode)

        assertThat(decoded.url).isEqualTo(access.url)
        assertThat(decoded.insecureHttpApproved).isTrue()
        assertThat(decoded.userAgent).isEqualTo("Provider Agent")
        assertThat(decoded.referrer).isEqualTo("http://portal.example/")
        assertThat(decoded.sensitiveHeaders).containsExactly(
            "Authorization",
            "Bearer private-value",
            "X-Api-Key",
            "private-api-key",
        )
        assertThat(decoded.approvedPlaybackOrigins.map(ExactHttpOrigin::encoded)).containsExactly(
            "http://provider.example:8080",
            "http://cdn.example:80",
            "http://video.example:8080",
        )
        assertThat(access.toString()).contains("approvedPlaybackOriginCount=3")
        assertThat(access.toString()).doesNotContain("secret-token")
        assertThat(access.toString()).doesNotContain("private-value")
        assertThat(access.toString()).doesNotContain("private-api-key")
        assertThat(access.toString()).doesNotContain("cdn.example")
    }

    @Test
    fun `approved origins use exact host and port`() {
        val access = RemoteSourceAccess(
            url = "http://provider.example/list.m3u",
            insecureHttpApproved = true,
            approvedPlaybackOrigins = setOf(
                origin("http://provider.example:80"),
                origin("http://cdn.example:8080"),
            ),
        )

        assertThat(access.approvesPlayback("http://provider.example/live")).isTrue()
        assertThat(access.approvesPlayback("http://provider.example:8080/live")).isFalse()
        assertThat(access.approvesPlayback("http://cdn.example:8080/live")).isTrue()
        assertThat(access.approvesPlayback("http://cdn.example/live")).isFalse()
        assertThat(access.approvesPlayback("https://provider.example/live")).isFalse()
    }

    @Test
    fun `approval mutations are immutable idempotent bounded and independent from refresh approval`() {
        val original = RemoteSourceAccess(
            url = "http://provider.example/list.m3u",
            insecureHttpApproved = true,
        )
        val first = origin("http://cdn-1.example:80")
        val approved = original.withApprovedPlaybackOrigin(first)

        assertThat(original.approvedPlaybackOrigins).isEmpty()
        assertThat(approved.approvedPlaybackOrigins).containsExactly(first)
        assertThat(approved.withApprovedPlaybackOrigin(first)).isSameInstanceAs(approved)
        assertThat(approved.withoutApprovedPlaybackOrigin(first).approvedPlaybackOrigins).isEmpty()
        val reset = approved.withoutPlaybackApprovals()
        assertThat(reset.approvedPlaybackOrigins).isEmpty()
        assertThat(reset.insecureHttpApproved).isTrue()

        val maximum = (1..RemoteSourceAccess.MAX_APPROVED_PLAYBACK_ORIGINS)
            .map { index -> origin("http://cdn-$index.example:80") }
            .toSet()
        val atCapacity = RemoteSourceAccess(
            url = "https://provider.example/list.m3u",
            approvedPlaybackOrigins = maximum,
        )

        assertThrows(PlaybackApprovalCapacityExceededException::class.java) {
            atCapacity.withApprovedPlaybackOrigin(origin("http://overflow.example:80"))
        }
    }

    @Test
    fun `changing source URL clears previous approvals and seeds only freshly approved source origin`() {
        val original = RemoteSourceAccess(
            url = "http://old.example/list.m3u",
            insecureHttpApproved = true,
            approvedPlaybackOrigins = setOf(
                origin("http://old.example:80"),
                origin("http://old-cdn.example:80"),
            ),
        )

        val changed = original.withSourceUrl(
            url = "http://new.example:8080/list.m3u",
            insecureHttpApproved = true,
        )

        assertThat(changed.approvedPlaybackOrigins.map(ExactHttpOrigin::encoded))
            .containsExactly("http://new.example:8080")
        assertThat(changed.approvesPlayback("http://old.example/live")).isFalse()
        assertThat(changed.approvesPlayback("http://old-cdn.example/live")).isFalse()
    }

    @Test
    fun `legacy v1 approved HTTP record seeds only the source origin`() {
        val decoded = RemoteSourceAccessCodec.decode(
            rawRecord(
                version = 1,
                url = "http://legacy.example:8080/list.m3u?token=legacy-secret",
                insecureHttpApproved = true,
            ),
        )

        assertThat(decoded.approvedPlaybackOrigins.map(ExactHttpOrigin::encoded))
            .containsExactly("http://legacy.example:8080")
        assertThat(decoded.approvesPlayback("http://other.example:8080/live")).isFalse()
    }

    @Test
    fun `legacy v1 HTTPS or denied HTTP records seed no playback origin`() {
        val secure = RemoteSourceAccessCodec.decode(
            rawRecord(version = 1, url = "https://secure.example/list.m3u", insecureHttpApproved = false),
        )
        val denied = RemoteSourceAccessCodec.decode(
            rawRecord(version = 1, url = "http://denied.example/list.m3u", insecureHttpApproved = false),
        )

        assertThat(secure.approvedPlaybackOrigins).isEmpty()
        assertThat(denied.approvedPlaybackOrigins).isEmpty()
    }

    @Test
    fun `v2 does not infer playback approval from source refresh approval`() {
        val decoded = RemoteSourceAccessCodec.decode(
            rawRecord(
                version = 2,
                url = "http://provider.example/list.m3u",
                insecureHttpApproved = true,
                origins = emptyList(),
            ),
        )

        assertThat(decoded.insecureHttpApproved).isTrue()
        assertThat(decoded.approvedPlaybackOrigins).isEmpty()
    }

    @Test
    fun `v2 rejects duplicate malformed and excessive origin entries`() {
        assertInvalidRecord(
            rawRecord(
                version = 2,
                url = "https://provider.example/list.m3u",
                insecureHttpApproved = false,
                origins = listOf("http://cdn.example:80", "http://cdn.example:80"),
            ),
        )
        assertInvalidRecord(
            rawRecord(
                version = 2,
                url = "https://provider.example/list.m3u",
                insecureHttpApproved = false,
                origins = listOf("https://not-http.example:443"),
            ),
        )
        assertInvalidRecord(
            rawRecord(
                version = 2,
                url = "https://provider.example/list.m3u",
                insecureHttpApproved = false,
                origins = (1..RemoteSourceAccess.MAX_APPROVED_PLAYBACK_ORIGINS + 1)
                    .map { index -> "http://cdn-$index.example:80" },
            ),
        )
    }

    @Test
    fun `v2 rejects trailing data`() {
        val valid = rawRecord(
            version = 2,
            url = "https://provider.example/list.m3u",
            insecureHttpApproved = false,
        )
        val withTrailingData = valid + byteArrayOf(1)

        val failure = assertThrows(RemoteSourceAccessFormatException::class.java) {
            RemoteSourceAccessCodec.decode(withTrailingData)
        }

        assertThat(failure.reason).isEqualTo(RemoteSourceAccessFormatReason.TrailingData)
    }

    private fun assertInvalidRecord(encoded: ByteArray) {
        val failure = assertThrows(RemoteSourceAccessFormatException::class.java) {
            RemoteSourceAccessCodec.decode(encoded)
        }
        assertThat(failure.reason).isAnyOf(
            RemoteSourceAccessFormatReason.InvalidField,
            RemoteSourceAccessFormatReason.TooManyOrigins,
            RemoteSourceAccessFormatReason.InvalidOrigin,
        )
    }

    private fun origin(encoded: String): ExactHttpOrigin =
        requireNotNull(ExactHttpOrigin.parse(encoded))

    private fun rawRecord(
        version: Int,
        url: String,
        insecureHttpApproved: Boolean,
        origins: List<String> = emptyList(),
    ): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte()))
            data.writeByte(version)
            data.writeBoolean(insecureHttpApproved)
            data.writeString(url)
            data.writeInt(-1)
            data.writeInt(-1)
            data.writeByte(0)
            if (version == 2) {
                data.writeByte(origins.size)
                origins.forEach(data::writeString)
            }
        }
        output.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}

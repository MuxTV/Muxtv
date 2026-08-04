package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerProxyRequestProfileDigestTest {
    @Test
    fun `same semantic request produces the same digest regardless of header insertion order`() {
        val first = request(
            headers = linkedMapOf(
                "User-Agent" to "MuxTV Measurement",
                "Referer" to "https://portal.example/player",
            ),
        )
        val second = request(
            headers = linkedMapOf(
                "Referer" to "https://portal.example/player",
                "User-Agent" to "MuxTV Measurement",
            ),
        )

        assertThat(PlayerProxyRequestProfileDigest.sha256(first))
            .isEqualTo(PlayerProxyRequestProfileDigest.sha256(second))
    }

    @Test
    fun `every request field and header boundary contributes to digest`() {
        val baseline = request()
        val baselineDigest = PlayerProxyRequestProfileDigest.sha256(baseline)
        val variants = listOf(
            baseline.copy(profileId = "profile-other"),
            baseline.copy(mediaId = "channel-2"),
            baseline.copy(variantId = "variant-2"),
            baseline.copy(locator = "https://stream.example/live/2.m3u8"),
            baseline.copy(displayName = "Renamed channel"),
            baseline.copy(displayName = null),
            baseline.copy(artworkUri = "https://images.example/2.png"),
            baseline.copy(artworkUri = null),
            baseline.copy(requestHeaders = mapOf("User-Agent" to "Changed")),
            baseline.copy(requestHeaders = mapOf("Referer" to "MuxTV Measurement")),
            baseline.copy(insecureHttpApproved = true),
        )

        assertThat(variants.map(PlayerProxyRequestProfileDigest::sha256))
            .doesNotContain(baselineDigest)
    }

    @Test
    fun `ordered request profile contributes to digest`() {
        val first = request(mediaId = "channel-1", variantId = "variant-1")
        val second = request(mediaId = "channel-2", variantId = "variant-2")
        val changed = request(mediaId = "channel-3", variantId = "variant-3")
        val baselineDigest = PlayerProxyRequestProfileDigest.sha256(listOf(first, second))

        assertThat(PlayerProxyRequestProfileDigest.sha256(listOf(first, changed)))
            .isNotEqualTo(baselineDigest)
        assertThat(PlayerProxyRequestProfileDigest.sha256(listOf(second, first)))
            .isNotEqualTo(baselineDigest)
    }

    @Test
    fun `request diagnostics do not expose typed identity display name or header names`() {
        val request = request(
            profileId = "profile-secret",
            mediaId = "channel-secret",
            variantId = "variant-secret",
            displayName = "Private Channel",
            headers = mapOf(
                "Authorization" to "Bearer secret",
                "X-Provider-Token" to "secret-token",
            ),
        )

        val text = request.toString()

        assertThat(text).doesNotContain("profile-secret")
        assertThat(text).doesNotContain("channel-secret")
        assertThat(text).doesNotContain("variant-secret")
        assertThat(text).doesNotContain("Private Channel")
        assertThat(text).doesNotContain("Authorization")
        assertThat(text).doesNotContain("X-Provider-Token")
        assertThat(text).doesNotContain("Bearer secret")
        assertThat(text).doesNotContain("secret-token")
    }

    private fun request(
        profileId: String = "profile-main",
        mediaId: String = "channel-1",
        variantId: String = "variant-1",
        displayName: String? = "Synthetic Channel",
        headers: Map<String, String> = mapOf("User-Agent" to "MuxTV Measurement"),
    ): PlaybackSessionRequest = PlaybackSessionRequest(
        profileId = profileId,
        mediaId = mediaId,
        variantId = variantId,
        locator = "https://stream.example/live/1.m3u8",
        displayName = displayName,
        artworkUri = "https://images.example/1.png",
        requestHeaders = headers,
        insecureHttpApproved = false,
    )
}

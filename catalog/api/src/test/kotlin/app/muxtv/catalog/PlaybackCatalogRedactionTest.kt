package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackCatalogRedactionTest {
    @Test
    fun `query and channel diagnostics omit user and provider controlled strings`() {
        val query = ChannelQuery(
            profileId = "profile-secret",
            searchText = "private search",
        )
        val summary = PlayableChannelSummary(
            channelId = "channel-safe",
            displayName = "Private Channel Name",
            logoUrl = "https://images.example/private-logo.png?token=secret",
            groupTitle = "Private Group",
            channelNumber = "private-number",
            isFavorite = true,
            variantCount = 1,
        )

        assertThat(query.toString()).doesNotContain("profile-secret")
        assertThat(query.toString()).doesNotContain("private search")
        assertThat(summary.toString()).doesNotContain("Private Channel Name")
        assertThat(summary.toString()).doesNotContain("private-logo")
        assertThat(summary.toString()).doesNotContain("Private Group")
        assertThat(summary.toString()).doesNotContain("private-number")
    }

    @Test
    fun `variant and approval diagnostics redact source identity and exact origin`() {
        val variant = PlayableVariant(
            variantId = "variant-secret",
            sourceId = "source-secret",
            sourceName = "Private Provider",
            locator = "http://private.example/live.m3u8?token=secret",
            userAgent = "Secret Agent",
            referrer = "https://portal.example/private",
        )
        val decision = PlaybackAccessDecision.ApprovalRequired(
            displayOrigin = "http://private.example:8080",
        )
        val resolution = PlaybackVariantResolution.InsecureTransportApprovalRequired(
            channelId = "channel-secret",
            variantId = "variant-secret",
            displayOrigin = "http://private.example:8080",
        )

        assertThat(variant.toString()).doesNotContain("variant-secret")
        assertThat(variant.toString()).doesNotContain("source-secret")
        assertThat(variant.toString()).doesNotContain("Private Provider")
        assertThat(variant.toString()).doesNotContain("private.example")
        assertThat(variant.toString()).doesNotContain("Secret Agent")
        assertThat(variant.toString()).doesNotContain("portal.example")
        assertThat(decision.toString()).doesNotContain("private.example")
        assertThat(resolution.toString()).doesNotContain("private.example")
        assertThat(resolution.toString()).doesNotContain("channel-secret")
        assertThat(resolution.toString()).doesNotContain("variant-secret")
    }
}

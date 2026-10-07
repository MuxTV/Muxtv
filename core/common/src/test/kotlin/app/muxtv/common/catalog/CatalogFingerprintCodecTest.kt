package app.muxtv.common.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogFingerprintCodecTest {
    @Test
    fun goldenVectorPinsProductionIdentityAndPayloadBytes() {
        val codec = CatalogFingerprintCodec()
        val logicalChannelId = codec.logicalChannelId(
            sourceId = "source-a",
            providerKey = "tvg:channel-one",
        )
        val fingerprint = codec.fingerprint(input())

        assertThat(logicalChannelId)
            .isEqualTo("feee6c5319b99580350fe732c916568d2ded27984ac4a35bed7e68c2c0c0765a")
        assertThat(fingerprint.contentHash)
            .isEqualTo("a567356333c3e56877b01343ef63999fb239c9b86e0fa0a907f2af8ac6fff4cf")
        assertThat(fingerprint.searchContentHash)
            .isEqualTo("15d3480da894cffb6c40c0ea0bea405fa69d8a988b646eb9071ca1beb0b54eff")
        assertThat(fingerprint.contentHashVersion).isEqualTo(1)
        assertThat(fingerprint.searchHashVersion).isEqualTo(1)
    }

    @Test
    fun logicalIdentityIsSourceScopedAndSecretIndependent() {
        val codec = CatalogFingerprintCodec()

        assertThat(codec.logicalChannelId("source-a", "tvg:channel-one"))
            .isEqualTo(codec.logicalChannelId("source-a", "tvg:channel-one"))
        assertThat(codec.logicalChannelId("source-a", "tvg:channel-one"))
            .isNotEqualTo(codec.logicalChannelId("source-b", "tvg:channel-one"))
    }

    @Test
    fun nullAndEmptyRemainDistinctAndInputToStringIsRedacted() {
        val codec = CatalogFingerprintCodec()
        val withNull = input().copy(groupTitle = null)
        val withEmpty = input().copy(groupTitle = "")

        assertThat(codec.fingerprint(withNull).contentHash)
            .isNotEqualTo(codec.fingerprint(withEmpty).contentHash)
        assertThat(codec.fingerprint(withNull).searchContentHash)
            .isNotEqualTo(codec.fingerprint(withEmpty).searchContentHash)

        val rendered = input().toString()
        assertThat(rendered).doesNotContain("https://stream.invalid")
        assertThat(rendered).doesNotContain("Channel One")
        assertThat(rendered).contains("<redacted>")
    }

    @Test
    fun locatorChurnChangesContentButNotProviderSearchFingerprint() {
        val codec = CatalogFingerprintCodec()
        val before = codec.fingerprint(input(locator = "https://stream.invalid/live?token=alpha"))
        val after = codec.fingerprint(input(locator = "https://stream.invalid/live?token=beta"))

        assertThat(before.contentHash).isNotEqualTo(after.contentHash)
        assertThat(before.searchContentHash).isEqualTo(after.searchContentHash)
    }

    private fun input(
        locator: String = "https://stream.invalid/live",
    ) = CatalogPayloadFingerprintInput(
        providerKey = "tvg:channel-one",
        canonicalChannelId = "6ad24a2ec9e65345d6ac47f18539d556025e2eb6457a96cf8f0698d2c8c49b38",
        rawName = "Channel One",
        tvgId = "channel-one",
        tvgName = "Channel One HD",
        logoUrl = "https://images.invalid/channel-one.png",
        groupTitle = "General",
        channelNumber = "1",
        catchupMode = "default",
        catchupSource = "${start}",
        catchupDays = 7,
        catchupCorrection = "+00:00",
        locator = locator,
        userAgent = "MuxTV-Test",
        referrer = "https://referrer.invalid/",
    )
}

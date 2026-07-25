package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.M3uEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogEntryIdentityFactoryTest {
    @Test
    fun tvgIdentityRemainsGloballyScopedAndByteCompatible() {
        val identity = CatalogEntryIdentityFactory().create(
            entry = entry(
                displayName = "Россия 1",
                tvgId = " ru-1 ",
                tvgName = "Россия 1",
                groupTitle = "Общие",
                channelNumber = "1",
            ),
            sourceId = "source-a",
            revisionNumber = 1,
            ordinal = 1,
        )

        assertThat(identity.providerKey).isEqualTo("tvg:ru-1")
        assertThat(identity.providerChannelId)
            .isEqualTo("5f56f2bb2e95c8a94acb9f3b29e9e00a53ec71ec531610e0af50129083b10f20")
        assertThat(identity.canonicalChannelId)
            .isEqualTo("971e0b3a8afcfa950e1327b7f19e342bcb29ba798b0c5cd0be7cdd214714f181")
        assertThat(identity.streamVariantId)
            .isEqualTo("d8654c40a309ad4cc8c171beff5c35cf0cc583e4355fadb79f925af6a87e9abe")
    }

    @Test
    fun fallbackIdentityPreservesCyrillicNormalizationAndSourceScope() {
        val identity = CatalogEntryIdentityFactory().create(
            entry = entry(
                displayName = "  Первый   Канал  ",
                groupTitle = "  Новости   РФ ",
                channelNumber = " 01 ",
            ),
            sourceId = "source-a",
            revisionNumber = 2,
            ordinal = 7,
        )

        assertThat(identity.providerKey)
            .isEqualTo("name:первый канал|group:новости рф|number:01")
        assertThat(identity.providerChannelId)
            .isEqualTo("67f45f611d91e55c8911ee7b3e62fc2a359f6ae090360164f0c343ed48af9119")
        assertThat(identity.canonicalChannelId)
            .isEqualTo("0b377b75d0104f967b4978fee725d1d848063291a516280f7f118c635c2954e8")
        assertThat(identity.streamVariantId)
            .isEqualTo("bd47c71b10931701620a17b66def33bc711604d8eb9dd6005c378e1a6c606e9e")
    }

    @Test
    fun fallbackCanonicalIdentityChangesAcrossSourcesButNotAcrossOrdinals() {
        val factory = CatalogEntryIdentityFactory()
        val entry = entry(
            displayName = "  Первый   Канал  ",
            groupTitle = "  Новости   РФ ",
            channelNumber = " 01 ",
        )

        val sourceAOrdinal7 = factory.create(entry, "source-a", 2, 7)
        val sourceAOrdinal8 = factory.create(entry, "source-a", 2, 8)
        val sourceBOrdinal7 = factory.create(entry, "source-b", 2, 7)

        assertThat(sourceAOrdinal7.canonicalChannelId)
            .isEqualTo(sourceAOrdinal8.canonicalChannelId)
        assertThat(sourceAOrdinal7.providerChannelId)
            .isNotEqualTo(sourceAOrdinal8.providerChannelId)
        assertThat(sourceAOrdinal7.streamVariantId)
            .isNotEqualTo(sourceAOrdinal8.streamVariantId)
        assertThat(sourceAOrdinal7.canonicalChannelId)
            .isNotEqualTo(sourceBOrdinal7.canonicalChannelId)
        assertThat(sourceBOrdinal7.canonicalChannelId)
            .isEqualTo("0e06f45b9c46a193e953689c028f2bedaf693442800b1ea2f182456b24102a35")
    }

    private fun entry(
        displayName: String,
        tvgId: String? = null,
        tvgName: String? = null,
        groupTitle: String? = null,
        channelNumber: String? = null,
    ): M3uEntry = M3uEntry(
        displayName = displayName,
        locator = "https://stream.example/live",
        durationSeconds = null,
        tvgId = tvgId,
        tvgName = tvgName,
        tvgLogo = null,
        groupTitle = groupTitle,
        channelNumber = channelNumber,
        catchupMode = null,
        catchupSource = null,
        catchupDays = null,
        catchupCorrection = null,
        userAgent = null,
        referrer = null,
        attributes = emptyMap(),
    )
}

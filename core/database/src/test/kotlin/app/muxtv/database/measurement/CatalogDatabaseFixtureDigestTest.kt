package app.muxtv.database.measurement

import app.muxtv.database.EpgChannelEntity
import app.muxtv.database.EpgProgrammeEntity
import app.muxtv.database.StagedCatalogEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogDatabaseFixtureDigestTest {
    @Test
    fun `same entries produce the same digest`() {
        val entries = listOf(entry())

        assertThat(CatalogDatabaseFixtureDigest.sha256(entries))
            .isEqualTo(CatalogDatabaseFixtureDigest.sha256(entries.map { it.copy() }))
    }

    @Test
    fun `every staged field contributes to the digest`() {
        val baseline = entry()
        val baselineDigest = CatalogDatabaseFixtureDigest.sha256(listOf(baseline))
        val variants = listOf(
            baseline.copy(providerChannelId = "provider-2"),
            baseline.copy(providerKey = "tvg:measurement-2"),
            baseline.copy(rawName = "Renamed channel"),
            baseline.copy(canonicalChannelId = "canonical-2"),
            baseline.copy(canonicalDisplayName = "Renamed canonical channel"),
            baseline.copy(streamVariantId = "variant-2"),
            baseline.copy(locator = "https://stream.example/live/2.m3u8"),
            baseline.copy(tvgId = "measurement-2"),
            baseline.copy(tvgName = "Renamed TVG channel"),
            baseline.copy(logoUrl = "https://images.example/channels/2.png"),
            baseline.copy(groupTitle = "Group 2"),
            baseline.copy(channelNumber = "2"),
            baseline.copy(userAgent = null),
            baseline.copy(referrer = null),
        )

        assertThat(variants.map { CatalogDatabaseFixtureDigest.sha256(listOf(it)) })
            .doesNotContain(baselineDigest)
    }

    @Test
    fun `entry boundaries are unambiguous`() {
        val first = listOf(
            entry(providerChannelId = "ab", providerKey = "c"),
        )
        val second = listOf(
            entry(providerChannelId = "a", providerKey = "bc"),
        )

        assertThat(CatalogDatabaseFixtureDigest.sha256(first))
            .isNotEqualTo(CatalogDatabaseFixtureDigest.sha256(second))
    }

    @Test
    fun `epg corpus contributes to the digest`() {
        val entries = listOf(entry())
        val channel = epgChannel()
        val programme = epgProgramme()
        val baseline = CatalogDatabaseFixtureDigest.sha256(entries, listOf(channel), listOf(programme))

        assertThat(
            CatalogDatabaseFixtureDigest.sha256(
                entries,
                listOf(channel.copy(primaryDisplayName = "Renamed EPG channel")),
                listOf(programme),
            ),
        ).isNotEqualTo(baseline)
        assertThat(
            CatalogDatabaseFixtureDigest.sha256(
                entries,
                listOf(channel),
                listOf(programme.copy(startEpochMillis = 2_000L)),
            ),
        ).isNotEqualTo(baseline)
    }

    private fun entry(
        providerChannelId: String = "provider-1",
        providerKey: String = "tvg:measurement-1",
    ): StagedCatalogEntry = StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = providerKey,
        rawName = "Synthetic Channel 1",
        canonicalChannelId = "canonical-1",
        canonicalDisplayName = "Synthetic Channel 1",
        streamVariantId = "variant-1",
        locator = "https://stream.example/live/1.m3u8",
        tvgId = "measurement-1",
        tvgName = "Synthetic Channel 1",
        logoUrl = "https://images.example/channels/1.png",
        groupTitle = "Group 1",
        channelNumber = "1",
        userAgent = "MuxTV-Measurement/1",
        referrer = "https://portal.example/measurement/1",
    )

    private fun epgChannel() = EpgChannelEntity(
        sourceId = "measurement-epg",
        revisionNumber = 1,
        externalId = "epg-1",
        primaryDisplayName = "Synthetic Channel 1",
        primaryLanguage = "en",
        iconRef = null,
    )

    private fun epgProgramme() = EpgProgrammeEntity(
        sourceId = "measurement-epg",
        revisionNumber = 1,
        sequenceNumber = 1,
        externalChannelId = "epg-1",
        startEpochMillis = 1_000L,
        stopEpochMillis = 3_000L,
        primaryTitle = "Programme 1",
        primaryLanguage = "en",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )
}

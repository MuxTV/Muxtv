package app.muxtv.catalog.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class C03CatalogPayloadIdentityContractTest {
    @Test
    fun logicalIdentityIsStableAcrossRevisionOrderAndLocatorTokenChurn() {
        val factory = CatalogEntryIdentityFactory()
        val baseline = entry(playbackReference = "https://stream.invalid/live?token=alpha")
        val tokenChurn = entry(playbackReference = "https://stream.invalid/live?token=beta")

        val revisionOne = factory.create(
            entry = baseline,
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            ordinal = 1,
        )
        val revisionTwo = factory.create(
            entry = tokenChurn,
            sourceId = SOURCE_ID,
            revisionNumber = 2,
            ordinal = 99,
        )

        assertThat(revisionOne.logicalChannelId).isEqualTo(revisionTwo.logicalChannelId)
        assertThat(revisionOne.providerChannelId).isNotEqualTo(revisionTwo.providerChannelId)
        assertThat(revisionOne.streamVariantId).isNotEqualTo(revisionTwo.streamVariantId)
    }

    @Test
    fun logicalIdentityIsSourceScopedAndDuplicatesDoNotRequireSecretDisambiguation() {
        val factory = CatalogEntryIdentityFactory()
        val duplicate = entry(playbackReference = "https://stream.invalid/live?token=secret-a")

        val first = factory.create(duplicate, SOURCE_ID, revisionNumber = 1, ordinal = 1)
        val second = factory.create(duplicate, SOURCE_ID, revisionNumber = 1, ordinal = 2)
        val otherSource = factory.create(duplicate, "source-b", revisionNumber = 1, ordinal = 1)

        assertThat(first.logicalChannelId).isEqualTo(second.logicalChannelId)
        assertThat(first.logicalChannelId).isNotEqualTo(otherSource.logicalChannelId)
        assertThat(first.providerChannelId).isNotEqualTo(second.providerChannelId)
    }

    @Test
    fun tokenChurnChangesContentHashButNotSearchContentHash() {
        val factory = CatalogEntryIdentityFactory()
        val baseline = entry(playbackReference = "https://stream.invalid/live?token=alpha")
        val tokenChurn = entry(playbackReference = "https://stream.invalid/live?token=beta")
        val baselineIdentity = factory.create(baseline, SOURCE_ID, revisionNumber = 1, ordinal = 1)
        val changedIdentity = factory.create(tokenChurn, SOURCE_ID, revisionNumber = 2, ordinal = 1)
        val fingerprinter = CatalogEntryPayloadFingerprinter()

        val before = fingerprinter.fingerprint(baseline, baselineIdentity)
        val after = fingerprinter.fingerprint(tokenChurn, changedIdentity)

        assertThat(before.contentHash).isNotEqualTo(after.contentHash)
        assertThat(before.searchContentHash).isEqualTo(after.searchContentHash)
        assertThat(before.contentHashVersion).isEqualTo(1)
        assertThat(before.searchHashVersion).isEqualTo(1)
    }

    @Test
    fun searchVisibleMetadataChangeChangesBothPayloadAndSearchHashes() {
        val factory = CatalogEntryIdentityFactory()
        val baseline = entry(groupTitle = "News")
        val changed = entry(groupTitle = "General")
        val baselineIdentity = factory.create(baseline, SOURCE_ID, revisionNumber = 1, ordinal = 1)
        val changedIdentity = factory.create(changed, SOURCE_ID, revisionNumber = 2, ordinal = 1)
        val fingerprinter = CatalogEntryPayloadFingerprinter()

        val before = fingerprinter.fingerprint(baseline, baselineIdentity)
        val after = fingerprinter.fingerprint(changed, changedIdentity)

        assertThat(before.contentHash).isNotEqualTo(after.contentHash)
        assertThat(before.searchContentHash).isNotEqualTo(after.searchContentHash)
    }

    @Test
    fun revisionAndOrdinalDoNotAffectPayloadFingerprints() {
        val factory = CatalogEntryIdentityFactory()
        val entry = entry()
        val firstIdentity = factory.create(entry, SOURCE_ID, revisionNumber = 1, ordinal = 1)
        val reorderedIdentity = factory.create(entry, SOURCE_ID, revisionNumber = 7, ordinal = 700)
        val fingerprinter = CatalogEntryPayloadFingerprinter()

        val first = fingerprinter.fingerprint(entry, firstIdentity)
        val reordered = fingerprinter.fingerprint(entry, reorderedIdentity)

        assertThat(first.contentHash).isEqualTo(reordered.contentHash)
        assertThat(first.searchContentHash).isEqualTo(reordered.searchContentHash)
    }

    private fun entry(
        playbackReference: String = "https://stream.invalid/live",
        groupTitle: String? = "General",
    ): CatalogImportEntry = CatalogImportEntry(
        providerStableId = null,
        displayName = "Channel One",
        playbackReference = playbackReference,
        tvgId = "channel-one",
        tvgName = "Channel One HD",
        logoUrl = "https://images.invalid/channel-one.png",
        groupTitle = groupTitle,
        channelNumber = "1",
        catchupMode = "default",
        catchupSource = "${'$'}{start}",
        catchupDays = 7,
        catchupCorrection = "+00:00",
        userAgent = "MuxTV-Test",
        referrer = "https://referrer.invalid/",
    )

    private companion object {
        const val SOURCE_ID = "source-a"
    }
}

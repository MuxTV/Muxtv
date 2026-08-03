package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchDocumentFactoryTest {
    @Test
    fun providerDocumentsUseDisplayMetadataAndCanonicalMappingOnly() {
        val provider = ProviderChannelEntity(
            id = "provider-a",
            sourceId = "source-a",
            revisionNumber = 3,
            providerKey = "provider-key",
            rawName = "Россия Первый",
            groupTitle = "Новости",
            channelNumber = "001",
        )
        val variant = StreamVariantEntity(
            id = "variant-a",
            providerChannelId = provider.id,
            canonicalChannelId = "channel-a",
            locator = "https://secret.example/live?token=secret",
            userAgent = "secret-agent",
            referrer = "https://secret.example/referrer",
        )

        val documents = providerSearchDocuments(
            providerChannels = listOf(provider),
            streamVariants = listOf(variant),
        )

        assertThat(documents.map(SearchDocumentEntity::kind)).containsExactly(
            SearchDocumentKind.PROVIDER_RAW_NAME,
            SearchDocumentKind.PROVIDER_GROUP,
            SearchDocumentKind.PROVIDER_NUMBER,
        )
        assertThat(documents.map(SearchDocumentEntity::canonicalChannelId).distinct())
            .containsExactly("channel-a")
        assertThat(documents.map(SearchDocumentEntity::text)).containsExactly(
            "Россия Первый",
            "Новости",
            "001",
        )
        documents.forEach { document ->
            val diagnostic = document.toString()
            assertThat(diagnostic).doesNotContain("secret")
            assertThat(diagnostic).doesNotContain("Россия")
        }
    }

    @Test
    fun blankOptionalProviderFieldsDoNotCreateDocuments() {
        val provider = ProviderChannelEntity(
            id = "provider-a",
            sourceId = "source-a",
            revisionNumber = 3,
            providerKey = "provider-key",
            rawName = "Канал",
            groupTitle = "   ",
            channelNumber = null,
        )
        val variant = StreamVariantEntity(
            id = "variant-a",
            providerChannelId = provider.id,
            canonicalChannelId = "channel-a",
            locator = "https://example.invalid/live",
        )

        val documents = providerSearchDocuments(listOf(provider), listOf(variant))

        assertThat(documents).hasSize(1)
        assertThat(documents.single().kind).isEqualTo(SearchDocumentKind.PROVIDER_RAW_NAME)
    }

    @Test
    fun programmeDocumentsCarryExactImmutableOrigin() {
        val programme = EpgProgrammeEntity(
            sourceId = "epg-a",
            revisionNumber = 7,
            sequenceNumber = 12,
            externalChannelId = "external-a",
            startEpochMillis = 1_000,
            stopEpochMillis = 2_000,
            primaryTitle = "Вести",
            primaryLanguage = "ru",
            subtitle = null,
            description = "not indexed",
            category = "not indexed",
            iconRef = null,
            episodeNumber = null,
            isNew = false,
        )

        val document = epgProgrammeSearchDocuments(listOf(programme)).single()

        assertThat(document.kind).isEqualTo(SearchDocumentKind.EPG_PROGRAMME_TITLE)
        assertThat(document.epgSourceId).isEqualTo("epg-a")
        assertThat(document.epgRevisionNumber).isEqualTo(7)
        assertThat(document.epgExternalChannelId).isEqualTo("external-a")
        assertThat(document.epgProgrammeSequence).isEqualTo(12)
        assertThat(document.text).isEqualTo("Вести")
        assertThat(document.toString()).doesNotContain("Вести")
        assertThat(document.toString()).doesNotContain("not indexed")
    }

    @Test
    fun canonicalDocumentUsesPublishedDisplayName() {
        val document = canonicalSearchDocuments(
            listOf(CanonicalChannelEntity(id = "channel-a", displayName = "Россия 1")),
        ).single()

        assertThat(document.kind).isEqualTo(SearchDocumentKind.CANONICAL_NAME)
        assertThat(document.canonicalChannelId).isEqualTo("channel-a")
        assertThat(document.text).isEqualTo("Россия 1")
    }
}

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
    fun programmeDocumentsCollapseRepeatedTitlesIntoVocabularyRows() {
        val first = programme(
            sourceId = "epg-a",
            revisionNumber = 7,
            sequenceNumber = 12,
            externalChannelId = "external-a",
            title = "Вести",
            description = "not indexed",
            category = "not indexed",
        )
        val repeated = programme(
            sourceId = "epg-a",
            revisionNumber = 7,
            sequenceNumber = 13,
            externalChannelId = "external-b",
            title = "Вести",
        )
        val secondTitle = programme(
            sourceId = "epg-a",
            revisionNumber = 7,
            sequenceNumber = 14,
            externalChannelId = "external-a",
            title = "Новости",
        )

        val documents = epgProgrammeSearchDocuments(listOf(first, repeated, secondTitle))

        assertThat(documents.map(SearchDocumentEntity::text))
            .containsExactly("Вести", "Новости")
            .inOrder()
        assertThat(documents.map(SearchDocumentEntity::kind).distinct())
            .containsExactly(SearchDocumentKind.EPG_PROGRAMME_TITLE)
        assertThat(documents.first().documentKey).contains("epg-a:7:12")
        documents.forEach { document ->
            assertThat(document.canonicalChannelId).isNull()
            assertThat(document.providerChannelId).isNull()
            assertThat(document.profileId).isNull()
            assertThat(document.toString()).doesNotContain("Вести")
            assertThat(document.toString()).doesNotContain("not indexed")
        }
    }

    @Test
    fun blankProgrammeTitlesDoNotCreateVocabularyRows() {
        val documents = epgProgrammeSearchDocuments(
            listOf(
                programme(
                    sourceId = "epg-a",
                    revisionNumber = 1,
                    sequenceNumber = 1,
                    externalChannelId = "external-a",
                    title = null,
                ),
            ),
        )

        assertThat(documents).isEmpty()
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

    private fun programme(
        sourceId: String,
        revisionNumber: Long,
        sequenceNumber: Long,
        externalChannelId: String,
        title: String?,
        description: String? = null,
        category: String? = null,
    ) = EpgProgrammeEntity(
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        sequenceNumber = sequenceNumber,
        externalChannelId = externalChannelId,
        startEpochMillis = 1_000,
        stopEpochMillis = 2_000,
        primaryTitle = title,
        primaryLanguage = "ru",
        subtitle = null,
        description = description,
        category = category,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )
}

package app.muxtv.database

internal fun canonicalSearchDocuments(
    channels: List<CanonicalChannelEntity>,
): List<SearchDocumentEntity> = channels.mapNotNull { channel ->
    channel.displayName.nonBlankOrNull()?.let { displayName ->
        SearchDocumentEntity(
            documentKey = "canonical-name:${channel.id}",
            kind = SearchDocumentKind.CANONICAL_NAME,
            canonicalChannelId = channel.id,
            text = displayName,
        )
    }
}

internal fun providerSearchDocuments(
    providerChannels: List<ProviderChannelEntity>,
    streamVariants: List<StreamVariantEntity>,
): List<SearchDocumentEntity> {
    if (providerChannels.isEmpty() || streamVariants.isEmpty()) return emptyList()

    val canonicalIdsByProvider = streamVariants
        .groupBy(StreamVariantEntity::providerChannelId)
        .mapValues { (_, variants) ->
            variants.map(StreamVariantEntity::canonicalChannelId).distinct().sorted()
        }

    return buildList {
        providerChannels.forEach { provider ->
            val canonicalIds = canonicalIdsByProvider[provider.id].orEmpty()
            canonicalIds.forEach { canonicalId ->
                add(
                    SearchDocumentEntity(
                        documentKey = "provider-raw:${provider.id}:$canonicalId",
                        kind = SearchDocumentKind.PROVIDER_RAW_NAME,
                        canonicalChannelId = canonicalId,
                        providerChannelId = provider.id,
                        text = provider.rawName,
                    ),
                )
                provider.groupTitle.nonBlankOrNull()?.let { groupTitle ->
                    add(
                        SearchDocumentEntity(
                            documentKey = "provider-group:${provider.id}:$canonicalId",
                            kind = SearchDocumentKind.PROVIDER_GROUP,
                            canonicalChannelId = canonicalId,
                            providerChannelId = provider.id,
                            text = groupTitle,
                        ),
                    )
                }
                provider.channelNumber.nonBlankOrNull()?.let { channelNumber ->
                    add(
                        SearchDocumentEntity(
                            documentKey = "provider-number:${provider.id}:$canonicalId",
                            kind = SearchDocumentKind.PROVIDER_NUMBER,
                            canonicalChannelId = canonicalId,
                            providerChannelId = provider.id,
                            text = channelNumber,
                        ),
                    )
                }
            }
        }
    }
}

internal fun overlaySearchDocuments(
    overlay: UserChannelOverlayEntity,
): List<SearchDocumentEntity> = buildList {
    overlay.customName.nonBlankOrNull()?.let { customName ->
        add(
            SearchDocumentEntity(
                documentKey = "overlay-name:${overlay.profileId}:${overlay.canonicalChannelId}",
                kind = SearchDocumentKind.OVERLAY_CUSTOM_NAME,
                canonicalChannelId = overlay.canonicalChannelId,
                profileId = overlay.profileId,
                text = customName,
            ),
        )
    }
    overlay.channelNumber?.let { channelNumber ->
        add(
            SearchDocumentEntity(
                documentKey = "overlay-number:${overlay.profileId}:${overlay.canonicalChannelId}",
                kind = SearchDocumentKind.OVERLAY_NUMBER,
                canonicalChannelId = overlay.canonicalChannelId,
                profileId = overlay.profileId,
                text = channelNumber.toString(),
            ),
        )
    }
}

internal fun epgProgrammeSearchDocuments(
    programmes: List<EpgProgrammeEntity>,
): List<SearchDocumentEntity> = programmes.mapNotNull { programme ->
    programme.primaryTitle.nonBlankOrNull()?.let { title ->
        SearchDocumentEntity(
            documentKey = "epg-title:${programme.sourceId}:${programme.revisionNumber}:${programme.sequenceNumber}",
            kind = SearchDocumentKind.EPG_PROGRAMME_TITLE,
            epgSourceId = programme.sourceId,
            epgRevisionNumber = programme.revisionNumber,
            epgExternalChannelId = programme.externalChannelId,
            epgProgrammeSequence = programme.sequenceNumber,
            text = title,
        )
    }
}

private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)

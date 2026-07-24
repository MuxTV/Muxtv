package app.muxtv.database

import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.ResolvedPlaybackRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomPlaybackCatalog(
    private val dao: PlaybackCatalogDao,
) : PlaybackCatalog {
    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        dao.observeActiveChannels(
            profileId = query.profileId,
            searchPattern = query.normalizedSearchText?.toLikePattern(),
            favoritesOnly = query.favoritesOnly,
            limit = query.limit,
        ).map { rows -> rows.map(ActiveChannelSummaryRow::toModel) }

    override suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): PlayableChannel? {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        val summary = dao.findActiveChannel(profileId, channelId)?.toModel() ?: return null
        val variants = dao.getActiveVariants(channelId).map(ActiveVariantRow::toModel)
        if (variants.isEmpty()) return null
        return PlayableChannel(
            summary = summary.copy(variantCount = variants.size),
            variants = variants,
        )
    }

    override suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): ResolvedPlaybackRequest? {
        val channel = getChannel(profileId, channelId) ?: return null
        val variant = preferredVariantId
            ?.let { id -> channel.variants.firstOrNull { it.variantId == id } }
            ?: channel.variants.first()
        return ResolvedPlaybackRequest(
            channelId = channel.summary.channelId,
            variantId = variant.variantId,
            locator = variant.locator,
            requestHeaders = buildMap {
                variant.userAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
                variant.referrer?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
            },
        )
    }
}

private fun ActiveChannelSummaryRow.toModel(): PlayableChannelSummary = PlayableChannelSummary(
    channelId = channelId,
    displayName = displayName,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    channelNumber = channelNumber,
    isFavorite = isFavorite,
    variantCount = variantCount,
)

private fun ActiveVariantRow.toModel(): PlayableVariant = PlayableVariant(
    variantId = variantId,
    sourceId = sourceId,
    sourceName = sourceName,
    locator = locator,
    userAgent = userAgent,
    referrer = referrer,
)

private fun String.toLikePattern(): String = buildString(length + 2) {
    append('%')
    for (character in this@toLikePattern) {
        when (character) {
            '\\', '%', '_' -> append('\\')
        }
        append(character)
    }
    append('%')
}

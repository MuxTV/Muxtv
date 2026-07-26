package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

data class ChannelQuery(
    val profileId: String,
    val searchText: String? = null,
    val favoritesOnly: Boolean = false,
    val limit: Int = DEFAULT_CHANNEL_LIMIT,
) {
    init {
        require(profileId.isNotBlank())
        require(limit in 1..MAX_CHANNEL_LIMIT)
    }

    val normalizedSearchText: String? = searchText
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    companion object {
        const val DEFAULT_CHANNEL_LIMIT = 200
        const val MAX_CHANNEL_LIMIT = 500
    }
}

data class PlayableChannelSummary(
    val channelId: String,
    val displayName: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val variantCount: Int,
) {
    init {
        require(channelId.isNotBlank())
        require(displayName.isNotBlank())
        require(variantCount > 0)
    }
}

data class PlayableVariant(
    val variantId: String,
    val sourceId: String,
    val sourceName: String,
    val locator: String,
    val userAgent: String?,
    val referrer: String?,
) {
    init {
        require(variantId.isNotBlank())
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(locator.isNotBlank())
    }

    override fun toString(): String =
        "PlayableVariant(variantId=$variantId, sourceId=$sourceId, sourceName=$sourceName, " +
            "locator=<redacted>, userAgent=${userAgent != null}, referrer=${referrer != null})"
}

data class PlayableChannel(
    val summary: PlayableChannelSummary,
    val variants: List<PlayableVariant>,
) {
    init {
        require(variants.isNotEmpty())
        require(variants.size == summary.variantCount)
    }
}

data class ResolvedPlaybackRequest(
    val channelId: String,
    val variantId: String,
    val locator: String,
    val requestHeaders: Map<String, String>,
) {
    init {
        require(channelId.isNotBlank())
        require(variantId.isNotBlank())
        require(locator.isNotBlank())
        require(requestHeaders.keys.none(String::isBlank))
    }

    override fun toString(): String =
        "ResolvedPlaybackRequest(channelId=$channelId, variantId=$variantId, " +
            "locator=<redacted>, requestHeaders=${requestHeaders.keys.sorted()})"
}

interface PlaybackCatalog {
    fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>>

    suspend fun getChannel(
        profileId: String,
        channelId: String,
    ): PlayableChannel?

    suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String? = null,
    ): ResolvedPlaybackRequest?
}

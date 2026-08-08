package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

const val MAX_PLAYBACK_CANDIDATES: Int = 3

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

    override fun toString(): String =
        "ChannelQuery(profileId=<redacted>, hasSearch=${normalizedSearchText != null}, " +
            "favoritesOnly=$favoritesOnly, limit=$limit)"

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

    override fun toString(): String =
        "PlayableChannelSummary(channelId=$channelId, displayName=<redacted>, " +
            "hasLogo=${logoUrl != null}, hasGroup=${groupTitle != null}, " +
            "hasChannelNumber=${channelNumber != null}, isFavorite=$isFavorite, " +
            "variantCount=$variantCount)"
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
        "PlayableVariant(variantId=<redacted>, sourceId=<redacted>, sourceName=<redacted>, " +
            "locator=<redacted>, hasUserAgent=${userAgent != null}, hasReferrer=${referrer != null})"
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
    val insecureHttpApproved: Boolean,
) {
    init {
        require(channelId.isNotBlank())
        require(variantId.isNotBlank())
        require(locator.isNotBlank())
        require(requestHeaders.keys.none(String::isBlank))
    }

    override fun toString(): String =
        "ResolvedPlaybackRequest(channelId=$channelId, variantId=$variantId, " +
            "locator=<redacted>, requestHeaders=${requestHeaders.keys.sorted()}, " +
            "insecureHttpApproved=$insecureHttpApproved)"
}

enum class PlaybackAccessUnavailableReason {
    InvalidLocator,
    CredentialNotFound,
    CredentialCorrupted,
    CredentialUnavailable,
}

sealed interface PlaybackVariantResolution {
    data class Ready(
        val request: ResolvedPlaybackRequest,
    ) : PlaybackVariantResolution

    data class InsecureTransportApprovalRequired(
        val channelId: String,
        val variantId: String,
        val displayOrigin: String,
    ) : PlaybackVariantResolution {
        init {
            require(channelId.isNotBlank())
            require(variantId.isNotBlank())
            require(displayOrigin.isNotBlank())
        }

        override fun toString(): String =
            "InsecureTransportApprovalRequired(channelId=<redacted>, " +
                "variantId=<redacted>, displayOrigin=<redacted>)"
    }

    data class AccessUnavailable(
        val reason: PlaybackAccessUnavailableReason,
    ) : PlaybackVariantResolution
}

data class PlaybackCandidateIdentity(
    val channelId: String,
    val variantId: String,
) {
    init {
        require(channelId.isNotBlank())
        require(variantId.isNotBlank())
    }

    override fun toString(): String =
        "PlaybackCandidateIdentity(channelId=<redacted>, variantId=<redacted>)"
}

interface PlaybackCandidateResolver {
    suspend fun getCandidates(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
        limit: Int,
    ): List<PlaybackCandidateIdentity>

    suspend fun resolveCandidate(
        profileId: String,
        candidate: PlaybackCandidateIdentity,
    ): PlaybackVariantResolution?
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
    ): PlaybackVariantResolution?

    suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult

    suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult
}

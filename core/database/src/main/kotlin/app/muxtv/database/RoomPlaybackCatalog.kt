package app.muxtv.database

import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomPlaybackCatalog(
    private val dao: PlaybackCatalogDao,
    private val accessPolicyResolver: PlaybackAccessPolicyResolver,
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
    ): PlaybackVariantResolution? {
        val selection = selectVariant(
            profileId = profileId,
            channelId = channelId,
            preferredVariantId = preferredVariantId,
        ) ?: return null
        return when (
            val decision = accessPolicyResolver.resolve(
                credentialRef = selection.variant.credentialRef.orEmpty(),
                playbackLocator = selection.variant.locator,
            )
        ) {
            PlaybackAccessDecision.SecureTransport -> PlaybackVariantResolution.Ready(
                selection.toRequest(insecureHttpApproved = false),
            )

            PlaybackAccessDecision.Approved -> PlaybackVariantResolution.Ready(
                selection.toRequest(insecureHttpApproved = true),
            )

            is PlaybackAccessDecision.ApprovalRequired ->
                PlaybackVariantResolution.InsecureTransportApprovalRequired(
                    channelId = selection.channelId,
                    variantId = selection.variant.variantId,
                    displayOrigin = decision.displayOrigin,
                )

            PlaybackAccessDecision.InvalidLocator -> PlaybackVariantResolution.AccessUnavailable(
                PlaybackAccessUnavailableReason.InvalidLocator,
            )

            PlaybackAccessDecision.CredentialNotFound -> PlaybackVariantResolution.AccessUnavailable(
                PlaybackAccessUnavailableReason.CredentialNotFound,
            )

            PlaybackAccessDecision.CredentialCorrupted -> PlaybackVariantResolution.AccessUnavailable(
                PlaybackAccessUnavailableReason.CredentialCorrupted,
            )

            PlaybackAccessDecision.CredentialUnavailable -> PlaybackVariantResolution.AccessUnavailable(
                PlaybackAccessUnavailableReason.CredentialUnavailable,
            )
        }
    }

    override suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult {
        val selection = selectVariant(profileId, channelId, variantId)
            ?: return PlaybackAccessMutationResult.NotFound
        val credentialRef = selection.variant.credentialRef
            ?: return PlaybackAccessMutationResult.NotFound
        return accessPolicyResolver.approve(credentialRef, selection.variant.locator)
    }

    override suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult {
        val selection = selectVariant(profileId, channelId, variantId)
            ?: return PlaybackAccessMutationResult.NotFound
        val credentialRef = selection.variant.credentialRef
            ?: return PlaybackAccessMutationResult.NotFound
        return accessPolicyResolver.revoke(credentialRef, selection.variant.locator)
    }

    private suspend fun selectVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): VariantSelection? {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        require(preferredVariantId == null || preferredVariantId.isNotBlank())
        val summary = dao.findActiveChannel(profileId, channelId) ?: return null
        val variants = dao.getActiveVariants(channelId)
        if (variants.isEmpty()) return null
        val variant = preferredVariantId
            ?.let { id -> variants.firstOrNull { it.variantId == id } }
            ?: variants.firstOrNull()
            ?: return null
        return VariantSelection(
            channelId = summary.channelId,
            variant = variant,
        )
    }

    private data class VariantSelection(
        val channelId: String,
        val variant: ActiveVariantRow,
    ) {
        fun toRequest(insecureHttpApproved: Boolean): ResolvedPlaybackRequest =
            ResolvedPlaybackRequest(
                channelId = channelId,
                variantId = variant.variantId,
                locator = variant.locator,
                requestHeaders = buildMap {
                    variant.userAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
                    variant.referrer?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
                },
                insecureHttpApproved = insecureHttpApproved,
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

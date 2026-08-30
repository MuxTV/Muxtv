package app.muxtv.database

import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.MAX_PLAYBACK_CANDIDATES
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomPlaybackCatalog(
    private val dao: PlaybackCatalogDao,
    private val accessPolicyResolver: PlaybackAccessPolicyResolver,
    playbackReferenceResolver: PlaybackReferenceResolver,
) : PlaybackCatalog, PlaybackCandidateResolver {
    private val accessCoordinator = PlaybackAccessCoordinator(
        referenceResolver = playbackReferenceResolver,
        accessPolicyResolver = accessPolicyResolver,
    )

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
        val candidates = getCandidates(
            profileId = profileId,
            channelId = channelId,
            preferredVariantId = preferredVariantId,
            limit = 1,
        )
        val candidate = if (preferredVariantId == null) {
            candidates.firstOrNull()
        } else {
            candidates.firstOrNull { it.variantId == preferredVariantId }
        } ?: return null
        return resolveCandidate(profileId, candidate)
    }

    override suspend fun getCandidates(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
        limit: Int,
    ): List<PlaybackCandidateIdentity> {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        require(limit in 1..MAX_PLAYBACK_CANDIDATES)
        return dao.getActiveVariantIdentities(
            profileId = profileId,
            channelId = channelId,
            preferredVariantId = preferredVariantId,
            limit = limit,
        ).map { row ->
            PlaybackCandidateIdentity(row.channelId, row.variantId)
        }
    }

    override suspend fun resolveCandidate(
        profileId: String,
        candidate: PlaybackCandidateIdentity,
    ): PlaybackVariantResolution? {
        require(profileId.isNotBlank())
        val variant = dao.findActiveVariantAccess(
            profileId = profileId,
            channelId = candidate.channelId,
            variantId = candidate.variantId,
        ) ?: return null
        return resolveAccess(variant)
    }

    private suspend fun resolveAccess(
        variant: ActiveVariantAccessRow,
    ): PlaybackVariantResolution = when (
        val access = accessCoordinator.resolve(
            credentialRef = variant.credentialRef.orEmpty(),
            playbackReference = variant.locator,
        )
    ) {
        is CoordinatedPlaybackAccess.Ready -> PlaybackVariantResolution.Ready(
            variant.toRequest(
                locator = access.locator,
                insecureHttpApproved = access.insecureHttpApproved,
            ),
        )

        is CoordinatedPlaybackAccess.ApprovalRequired ->
            PlaybackVariantResolution.InsecureTransportApprovalRequired(
                channelId = variant.channelId,
                variantId = variant.variantId,
                displayOrigin = access.displayOrigin,
            )

        is CoordinatedPlaybackAccess.Unavailable ->
            PlaybackVariantResolution.AccessUnavailable(access.reason)
    }

    override suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult {
        val variant = dao.findActiveVariantAccess(profileId, channelId, variantId)
            ?: return PlaybackAccessMutationResult.NotFound
        val credentialRef = variant.credentialRef
            ?: return PlaybackAccessMutationResult.NotFound
        return accessPolicyResolver.approve(credentialRef, variant.locator)
    }

    override suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult {
        val variant = dao.findActiveVariantAccess(profileId, channelId, variantId)
            ?: return PlaybackAccessMutationResult.NotFound
        val credentialRef = variant.credentialRef
            ?: return PlaybackAccessMutationResult.NotFound
        return accessPolicyResolver.revoke(credentialRef, variant.locator)
    }
}

private fun ActiveVariantAccessRow.toRequest(
    locator: String,
    insecureHttpApproved: Boolean,
): ResolvedPlaybackRequest = ResolvedPlaybackRequest(
    channelId = channelId,
    variantId = variantId,
    locator = locator,
    requestHeaders = buildMap {
        userAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
        referrer?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
    },
    insecureHttpApproved = insecureHttpApproved,
)

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

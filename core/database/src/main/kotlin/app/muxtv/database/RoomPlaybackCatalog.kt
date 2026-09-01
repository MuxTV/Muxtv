package app.muxtv.database

import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.MAX_PLAYBACK_CANDIDATES
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackArchiveUnavailableReason
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.catalog.UnhandledPlaybackArchiveResolver
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomPlaybackCatalog(
    private val dao: PlaybackCatalogDao,
    private val accessPolicyResolver: PlaybackAccessPolicyResolver,
    playbackReferenceResolver: PlaybackReferenceResolver,
    private val playbackArchiveResolver: PlaybackArchiveResolver = UnhandledPlaybackArchiveResolver,
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
        val candidate = selectCandidate(
            profileId = profileId,
            channelId = channelId,
            preferredVariantId = preferredVariantId,
        ) ?: return null
        return resolveCandidate(profileId, candidate)
    }

    override suspend fun resolveIntent(
        profileId: String,
        intent: PlaybackIntent,
        preferredVariantId: String?,
    ): PlaybackVariantResolution? {
        if (intent is PlaybackIntent.Live) {
            return resolveVariant(
                profileId = profileId,
                channelId = intent.channelId,
                preferredVariantId = preferredVariantId,
            )
        }

        val candidate = selectCandidate(
            profileId = profileId,
            channelId = intent.channelId,
            preferredVariantId = preferredVariantId,
        ) ?: return null
        val variant = dao.findActiveVariantAccess(
            profileId = profileId,
            channelId = candidate.channelId,
            variantId = candidate.variantId,
        ) ?: return null

        return when (
            val archive = playbackArchiveResolver.resolve(
                PlaybackArchiveRequest(
                    intent = intent,
                    livePlaybackReference = variant.locator,
                    metadata = PlaybackArchiveMetadata(
                        mode = variant.catchupMode,
                        source = variant.catchupSource,
                        days = variant.catchupDays,
                        correction = variant.catchupCorrection,
                    ),
                ),
            )
        ) {
            PlaybackArchiveResolution.NotApplicable ->
                PlaybackVariantResolution.AccessUnavailable(
                    PlaybackAccessUnavailableReason.ArchiveUnsupported,
                )

            is PlaybackArchiveResolution.Unavailable ->
                PlaybackVariantResolution.AccessUnavailable(archive.reason.toAccessReason())

            is PlaybackArchiveResolution.Ready ->
                resolveAccess(
                    variant = variant,
                    playbackReference = archive.locator,
                    timeline = archive.timeline,
                )
        }
    }

    private suspend fun selectCandidate(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): PlaybackCandidateIdentity? {
        val candidates = getCandidates(
            profileId = profileId,
            channelId = channelId,
            preferredVariantId = preferredVariantId,
            limit = 1,
        )
        return if (preferredVariantId == null) {
            candidates.firstOrNull()
        } else {
            candidates.firstOrNull { it.variantId == preferredVariantId }
        }
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
        playbackReference: String = variant.locator,
        timeline: ResolvedPlaybackTimeline? = null,
    ): PlaybackVariantResolution = when (
        val access = accessCoordinator.resolve(
            credentialRef = variant.credentialRef.orEmpty(),
            playbackReference = playbackReference,
        )
    ) {
        is CoordinatedPlaybackAccess.Ready -> PlaybackVariantResolution.Ready(
            variant.toRequest(
                locator = access.locator,
                insecureHttpApproved = access.insecureHttpApproved,
                timeline = timeline,
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

private fun PlaybackArchiveUnavailableReason.toAccessReason(): PlaybackAccessUnavailableReason =
    when (this) {
        PlaybackArchiveUnavailableReason.OutsideRetention ->
            PlaybackAccessUnavailableReason.ArchiveOutsideRetention
        PlaybackArchiveUnavailableReason.UnsupportedMode ->
            PlaybackAccessUnavailableReason.ArchiveUnsupported
        PlaybackArchiveUnavailableReason.InvalidMetadata ->
            PlaybackAccessUnavailableReason.ArchiveInvalidMetadata
    }

private fun ActiveVariantAccessRow.toRequest(
    locator: String,
    insecureHttpApproved: Boolean,
    timeline: ResolvedPlaybackTimeline? = null,
): ResolvedPlaybackRequest = ResolvedPlaybackRequest(
    channelId = channelId,
    variantId = variantId,
    locator = locator,
    requestHeaders = buildMap {
        userAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
        referrer?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
    },
    insecureHttpApproved = insecureHttpApproved,
    timeline = timeline,
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

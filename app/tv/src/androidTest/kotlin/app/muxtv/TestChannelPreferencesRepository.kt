package app.muxtv

import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository

internal object NoChannelPreferencesRepository : ChannelPreferencesRepository {
    override suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult = ChannelFavoriteMutationResult.NotFound

    override suspend fun setHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.NotFound

    override suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.NotFound

    override suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.NotFound

    override suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.NotFound
}

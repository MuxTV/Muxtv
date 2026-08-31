package app.muxtv.database

import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository

internal class RoomChannelPreferencesRepository(
    private val dao: ChannelPreferencesDao,
) : ChannelPreferencesRepository {
    override suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult = when (dao.setFavorite(profileId, channelId, isFavorite)) {
        FavoriteWriteResult.Applied -> ChannelFavoriteMutationResult.Applied
        FavoriteWriteResult.Unchanged -> ChannelFavoriteMutationResult.Unchanged
        FavoriteWriteResult.NotFound -> ChannelFavoriteMutationResult.NotFound
    }

    override suspend fun setHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.InvalidInput

    override suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.InvalidInput

    override suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.InvalidInput

    override suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): ChannelPreferenceMutationResult = ChannelPreferenceMutationResult.InvalidInput
}

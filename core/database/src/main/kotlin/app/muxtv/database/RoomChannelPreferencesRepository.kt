package app.muxtv.database

import app.muxtv.catalog.ChannelFavoriteMutationResult
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
}

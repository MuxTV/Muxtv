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
    ): ChannelPreferenceMutationResult = dao.setHidden(profileId, channelId, isHidden).toApiResult()

    override suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult = dao.setCustomName(profileId, channelId, customName).toApiResult()

    override suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult = dao.setChannelNumber(profileId, channelId, channelNumber).toApiResult()

    override suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): ChannelPreferenceMutationResult = dao.resetCustomization(profileId, channelId).toApiResult()
}

private fun PreferenceWriteResult.toApiResult(): ChannelPreferenceMutationResult = when (this) {
    PreferenceWriteResult.Applied -> ChannelPreferenceMutationResult.Applied
    PreferenceWriteResult.Unchanged -> ChannelPreferenceMutationResult.Unchanged
    PreferenceWriteResult.NotFound -> ChannelPreferenceMutationResult.NotFound
    PreferenceWriteResult.InvalidInput -> ChannelPreferenceMutationResult.InvalidInput
}

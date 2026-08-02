package app.muxtv.catalog

interface ChannelPreferencesRepository {
    suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult
}

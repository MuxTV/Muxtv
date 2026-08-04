package app.muxtv.catalog

enum class ChannelFavoriteMutationResult {
    Applied,
    Unchanged,
    NotFound,
}

interface ChannelPreferencesRepository {
    suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult
}

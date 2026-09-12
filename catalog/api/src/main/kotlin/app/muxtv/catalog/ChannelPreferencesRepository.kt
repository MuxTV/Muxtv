package app.muxtv.catalog

enum class ChannelFavoriteMutationResult {
    Applied,
    Unchanged,
    NotFound,
}

enum class ChannelPreferenceMutationResult {
    Applied,
    Unchanged,
    NotFound,
    InvalidInput,
}

interface ChannelPreferencesRepository {
    suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult

    suspend fun setHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): ChannelPreferenceMutationResult

    suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult

    suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult

    suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): ChannelPreferenceMutationResult
}

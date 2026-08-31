package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelFavoriteMutationResult
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository

internal enum class ChannelQuickActionKind {
    FAVORITE,
    HIDE,
    RENAME,
    CHANNEL_NUMBER,
    RESET,
}

internal data class ChannelQuickAction(
    val kind: ChannelQuickActionKind,
    val label: String,
)

internal fun quickActionsFor(isFavorite: Boolean): List<ChannelQuickAction> = listOf(
    ChannelQuickAction(
        kind = ChannelQuickActionKind.FAVORITE,
        label = if (isFavorite) "Убрать из избранного" else "В избранное",
    ),
    ChannelQuickAction(ChannelQuickActionKind.HIDE, "Скрыть"),
    ChannelQuickAction(ChannelQuickActionKind.RENAME, "Переименовать"),
    ChannelQuickAction(ChannelQuickActionKind.CHANNEL_NUMBER, "Номер"),
    ChannelQuickAction(ChannelQuickActionKind.RESET, "Сбросить"),
)

internal class ChannelQuickActionsController(
    private val channelPreferencesRepository: ChannelPreferencesRepository,
    private val profileId: String,
) {
    init {
        require(profileId.isNotBlank())
    }

    suspend fun setFavorite(
        channelId: String,
        isFavorite: Boolean,
    ): ChannelFavoriteMutationResult = channelPreferencesRepository.setFavorite(
        profileId = profileId,
        channelId = channelId,
        isFavorite = isFavorite,
    )

    suspend fun hide(channelId: String): ChannelPreferenceMutationResult =
        channelPreferencesRepository.setHidden(
            profileId = profileId,
            channelId = channelId,
            isHidden = true,
        )

    suspend fun setCustomName(
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult = channelPreferencesRepository.setCustomName(
        profileId = profileId,
        channelId = channelId,
        customName = customName,
    )

    suspend fun setChannelNumber(
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult = channelPreferencesRepository.setChannelNumber(
        profileId = profileId,
        channelId = channelId,
        channelNumber = channelNumber,
    )

    suspend fun resetCustomization(channelId: String): ChannelPreferenceMutationResult =
        channelPreferencesRepository.resetCustomization(
            profileId = profileId,
            channelId = channelId,
        )
}

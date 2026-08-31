package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelManagementItem

internal data class ManageChannelRowUiModel(
    val channelId: String,
    val displayName: String,
    val originalDisplayName: String?,
    val channelNumber: String?,
    val defaultChannelNumber: String?,
    val customChannelNumber: Int?,
    val hasCustomName: Boolean,
    val hasCustomNumber: Boolean,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val variantCount: Int,
)

internal fun buildManageChannelRow(item: ChannelManagementItem): ManageChannelRowUiModel {
    val hasCustomName = item.effectiveDisplayName != item.canonicalDisplayName
    return ManageChannelRowUiModel(
        channelId = item.channelId,
        displayName = item.effectiveDisplayName,
        originalDisplayName = item.canonicalDisplayName.takeIf { hasCustomName },
        channelNumber = item.effectiveChannelNumber,
        defaultChannelNumber = item.defaultChannelNumber,
        customChannelNumber = item.customChannelNumber,
        hasCustomName = hasCustomName,
        hasCustomNumber = item.customChannelNumber != null,
        isFavorite = item.isFavorite,
        isHidden = item.isHidden,
        variantCount = item.variantCount,
    )
}

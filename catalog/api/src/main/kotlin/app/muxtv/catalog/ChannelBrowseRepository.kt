package app.muxtv.catalog

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

data class ChannelBrowseQuery(
    val profileId: String,
    val filter: ChannelBrowseFilter,
) {
    init {
        require(profileId.isNotBlank())
    }

    override fun toString(): String =
        "ChannelBrowseQuery(profileId=<redacted>, filter=$filter)"
}

enum class ChannelBrowseFilter {
    ALL,
    FAVORITES,
    RECENT,
}

data class ChannelBrowseItem(
    val channelId: String,
    val displayName: String,
    val channelNumber: String?,
    val groupTitle: String?,
    val isFavorite: Boolean,
    val isCurrentPlayback: Boolean,
    val currentProgrammeTitle: String?,
    val currentProgrammeEndEpochMillis: Long?,
    val nextProgrammeTitle: String?,
    val nextProgrammeStartEpochMillis: Long?,
    val variantCount: Int,
    val guideState: GuideProjectionState,
) {
    init {
        require(channelId.isNotBlank())
        require(displayName.isNotBlank())
        require(variantCount > 0)
        require(currentProgrammeEndEpochMillis == null || currentProgrammeEndEpochMillis >= 0L)
        require(nextProgrammeStartEpochMillis == null || nextProgrammeStartEpochMillis >= 0L)
        if (guideState != GuideProjectionState.READY) {
            require(currentProgrammeTitle == null)
            require(currentProgrammeEndEpochMillis == null)
            require(nextProgrammeTitle == null)
            require(nextProgrammeStartEpochMillis == null)
        }
    }

    override fun toString(): String =
        "ChannelBrowseItem(channelId=<redacted>, displayName=<redacted>, " +
            "hasChannelNumber=${channelNumber != null}, hasGroup=${groupTitle != null}, " +
            "isFavorite=$isFavorite, isCurrentPlayback=$isCurrentPlayback, " +
            "currentProgrammePresent=${currentProgrammeTitle != null}, " +
            "nextProgrammePresent=${nextProgrammeTitle != null}, variantCount=$variantCount, " +
            "guideState=$guideState)"
}

enum class ChannelManagementVisibility {
    ALL,
    VISIBLE,
    HIDDEN,
}

data class ChannelManagementQuery(
    val profileId: String,
    val visibility: ChannelManagementVisibility,
) {
    init {
        require(profileId.isNotBlank())
    }

    override fun toString(): String =
        "ChannelManagementQuery(profileId=<redacted>, visibility=$visibility)"
}

data class ChannelManagementItem(
    val channelId: String,
    val canonicalDisplayName: String,
    val effectiveDisplayName: String,
    val defaultChannelNumber: String?,
    val customChannelNumber: Int?,
    val effectiveChannelNumber: String?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val variantCount: Int,
) {
    init {
        require(channelId.isNotBlank())
        require(canonicalDisplayName.isNotBlank())
        require(effectiveDisplayName.isNotBlank())
        require(variantCount > 0)
    }

    override fun toString(): String =
        "ChannelManagementItem(channelId=<redacted>, canonicalDisplayName=<redacted>, " +
            "effectiveDisplayName=<redacted>, defaultChannelNumberPresent=${defaultChannelNumber != null}, " +
            "customChannelNumber=$customChannelNumber, effectiveChannelNumberPresent=${effectiveChannelNumber != null}, " +
            "isFavorite=$isFavorite, isHidden=$isHidden, variantCount=$variantCount)"
}

interface ChannelBrowseRepository {
    fun pages(query: ChannelBrowseQuery): Flow<PagingData<ChannelBrowseItem>>

    fun managementPages(query: ChannelManagementQuery): Flow<PagingData<ChannelManagementItem>>
}

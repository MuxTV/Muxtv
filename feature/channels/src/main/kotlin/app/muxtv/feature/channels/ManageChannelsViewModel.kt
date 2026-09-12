package app.muxtv.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelManagementItem
import app.muxtv.catalog.ChannelManagementQuery
import app.muxtv.catalog.ChannelManagementVisibility
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ManageChannelsFilter {
    ALL,
    VISIBLE,
    HIDDEN,
}

class ManageChannelsViewModel(
    private val channelBrowseRepository: ChannelBrowseRepository,
    private val channelPreferencesRepository: ChannelPreferencesRepository,
    private val profileId: String,
) : ViewModel() {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank." }
    }

    private val mutableFilter = MutableStateFlow(ManageChannelsFilter.ALL)
    val filter: StateFlow<ManageChannelsFilter> = mutableFilter.asStateFlow()

    private val rowsByFilter =
        mutableMapOf<ManageChannelsFilter, Flow<PagingData<ChannelManagementItem>>>()

    fun setFilter(filter: ManageChannelsFilter) {
        mutableFilter.value = filter
    }

    fun rowsFor(filter: ManageChannelsFilter): Flow<PagingData<ChannelManagementItem>> =
        rowsByFilter.getOrPut(filter) {
            channelBrowseRepository
                .managementPages(
                    ChannelManagementQuery(
                        profileId = profileId,
                        visibility = filter.toManagementVisibility(),
                    ),
                )
                .cachedIn(viewModelScope)
        }

    suspend fun setHidden(
        channelId: String,
        isHidden: Boolean,
    ): ChannelPreferenceMutationResult =
        channelPreferencesRepository.setHidden(
            profileId = profileId,
            channelId = channelId,
            isHidden = isHidden,
        )

    suspend fun setCustomName(
        channelId: String,
        customName: String?,
    ): ChannelPreferenceMutationResult =
        channelPreferencesRepository.setCustomName(
            profileId = profileId,
            channelId = channelId,
            customName = customName,
        )

    suspend fun setChannelNumber(
        channelId: String,
        channelNumber: Int?,
    ): ChannelPreferenceMutationResult =
        channelPreferencesRepository.setChannelNumber(
            profileId = profileId,
            channelId = channelId,
            channelNumber = channelNumber,
        )

    suspend fun resetCustomization(
        channelId: String,
    ): ChannelPreferenceMutationResult =
        channelPreferencesRepository.resetCustomization(
            profileId = profileId,
            channelId = channelId,
        )

    private fun ManageChannelsFilter.toManagementVisibility(): ChannelManagementVisibility =
        when (this) {
            ManageChannelsFilter.ALL -> ChannelManagementVisibility.ALL
            ManageChannelsFilter.VISIBLE -> ChannelManagementVisibility.VISIBLE
            ManageChannelsFilter.HIDDEN -> ChannelManagementVisibility.HIDDEN
        }
}

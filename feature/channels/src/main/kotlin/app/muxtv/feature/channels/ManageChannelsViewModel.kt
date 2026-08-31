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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ManageChannelsFilter {
    ALL,
    VISIBLE,
    HIDDEN,
}

internal class ManageChannelsViewModel(
    private val channelBrowseRepository: ChannelBrowseRepository,
    private val channelPreferencesRepository: ChannelPreferencesRepository,
    private val profileId: String,
) : ViewModel() {
    private val mutableFilter = MutableStateFlow(ManageChannelsFilter.ALL)
    val filter: StateFlow<ManageChannelsFilter> = mutableFilter.asStateFlow()

    private val mutableLastMutationResult = MutableStateFlow<ChannelPreferenceMutationResult?>(null)
    val lastMutationResult: StateFlow<ChannelPreferenceMutationResult?> =
        mutableLastMutationResult.asStateFlow()

    private val rowsByFilter = mutableMapOf<ManageChannelsFilter, Flow<PagingData<ChannelManagementItem>>>()
    private val mutationMutex = Mutex()

    init {
        require(profileId.isNotBlank())
    }

    fun rowsFor(filter: ManageChannelsFilter): Flow<PagingData<ChannelManagementItem>> =
        rowsByFilter.getOrPut(filter) {
            channelBrowseRepository.managementPages(
                ChannelManagementQuery(
                    profileId = profileId,
                    visibility = filter.toManagementVisibility(),
                ),
            ).cachedIn(viewModelScope)
        }

    fun setFilter(filter: ManageChannelsFilter) {
        mutableFilter.value = filter
    }

    fun setHidden(channelId: String, isHidden: Boolean) {
        mutate(channelId) {
            channelPreferencesRepository.setHidden(
                profileId = profileId,
                channelId = channelId,
                isHidden = isHidden,
            )
        }
    }

    fun setCustomName(channelId: String, customName: String?) {
        mutate(channelId) {
            channelPreferencesRepository.setCustomName(
                profileId = profileId,
                channelId = channelId,
                customName = customName,
            )
        }
    }

    fun setChannelNumber(channelId: String, channelNumber: Int?) {
        mutate(channelId) {
            channelPreferencesRepository.setChannelNumber(
                profileId = profileId,
                channelId = channelId,
                channelNumber = channelNumber,
            )
        }
    }

    fun resetCustomization(channelId: String) {
        mutate(channelId) {
            channelPreferencesRepository.resetCustomization(
                profileId = profileId,
                channelId = channelId,
            )
        }
    }

    fun clearMutationResult() {
        mutableLastMutationResult.value = null
    }

    private fun mutate(
        channelId: String,
        mutation: suspend () -> ChannelPreferenceMutationResult,
    ) {
        require(channelId.isNotBlank())
        viewModelScope.launch {
            val result = mutationMutex.withLock { mutation() }
            mutableLastMutationResult.value = result
        }
    }
}

private fun ManageChannelsFilter.toManagementVisibility(): ChannelManagementVisibility = when (this) {
    ManageChannelsFilter.ALL -> ChannelManagementVisibility.ALL
    ManageChannelsFilter.VISIBLE -> ChannelManagementVisibility.VISIBLE
    ManageChannelsFilter.HIDDEN -> ChannelManagementVisibility.HIDDEN
}

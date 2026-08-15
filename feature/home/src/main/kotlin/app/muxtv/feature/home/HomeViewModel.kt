package app.muxtv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    recentChannelsRepository: RecentChannelsRepository,
    private val epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    hasSources: Flow<Boolean>,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val hasSources: StateFlow<Boolean> = hasSources
        .catch { _: Throwable -> emit(false) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val playbackSessionState: StateFlow<PlaybackSessionState> =
        playbackSessionStateSource.playbackSessionState

    val recent: StateFlow<List<RecentChannel>> = recentChannelsRepository
        .observeRecent(RecentChannelsQuery(profileId = profileId, limit = HOME_RAIL_LIMIT))
        .catch { _: Throwable -> emit(emptyList()) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private val _nowNext = MutableStateFlow<Map<String, ChannelNowNext>>(emptyMap())
    val nowNext: StateFlow<Map<String, ChannelNowNext>> = _nowNext.asStateFlow()

    private val nowNextIds = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                refreshNowNext(nowNextIds.value)
                delay(NOW_NEXT_REFRESH_MILLIS)
            }
        }
    }

    fun setNowNextIds(channelIds: List<String>) {
        val normalized = channelIds.distinct().filter(String::isNotBlank).take(NowNextQuery.MAX_CHANNEL_IDS)
        if (normalized != nowNextIds.value) {
            nowNextIds.value = normalized
        }
    }

    fun refreshNowNext(channelIds: List<String>) {
        if (channelIds.isEmpty()) {
            _nowNext.value = emptyMap()
            return
        }
        viewModelScope.launch {
            try {
                val query = NowNextQuery(
                    profileId = profileId,
                    canonicalChannelIds = channelIds,
                    nowEpochMillis = nowEpochMillis(),
                )
                _nowNext.value = epgGuideRepository.getNowNext(query)
                    .associateBy { it.canonicalChannelId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _nowNext.value = emptyMap()
            }
        }
    }

    private companion object {
        const val NOW_NEXT_REFRESH_MILLIS = 60_000L
    }
}

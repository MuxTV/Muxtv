package app.muxtv.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.map
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelManagementItem
import app.muxtv.catalog.ChannelManagementQuery
import app.muxtv.catalog.ChannelManagementVisibility
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomChannelBrowseRepository(
    private val dao: ChannelBrowseDao,
    private val guideRepository: EpgGuideRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ChannelBrowseRepository {
    override fun pages(query: ChannelBrowseQuery): Flow<PagingData<ChannelBrowseItem>> =
        guideRepository.observeDataChanges()
            .onStart { emit(Unit) }
            .catch { emit(Unit) }
            .conflate()
            .flatMapLatest {
                Pager(
                    config = CHANNEL_BROWSE_PAGING_CONFIG,
                    pagingSourceFactory = {
                        GuideEnrichedChannelPagingSource(
                            delegate = when (query.filter) {
                                ChannelBrowseFilter.ALL -> dao.pageActiveChannels(
                                    profileId = query.profileId,
                                    favoritesOnly = false,
                                )

                                ChannelBrowseFilter.FAVORITES -> dao.pageActiveChannels(
                                    profileId = query.profileId,
                                    favoritesOnly = true,
                                )

                                ChannelBrowseFilter.RECENT -> dao.pageRecentChannels(query.profileId)
                            },
                            profileId = query.profileId,
                            guideRepository = guideRepository,
                            nowEpochMillis = nowEpochMillis,
                        )
                    },
                ).flow
            }

    override fun managementPages(query: ChannelManagementQuery): Flow<PagingData<ChannelManagementItem>> =
        Pager(
            config = CHANNEL_BROWSE_PAGING_CONFIG,
            pagingSourceFactory = {
                dao.pageManagedChannels(
                    profileId = query.profileId,
                    hiddenState = when (query.visibility) {
                        ChannelManagementVisibility.ALL -> null
                        ChannelManagementVisibility.VISIBLE -> 0
                        ChannelManagementVisibility.HIDDEN -> 1
                    },
                )
            },
        ).flow.map { pagingData ->
            pagingData.map(ActiveChannelManagementRow::toModel)
        }
}

internal val CHANNEL_BROWSE_PAGING_CONFIG = PagingConfig(
    pageSize = 64,
    initialLoadSize = 64,
    prefetchDistance = 16,
    maxSize = 256,
    enablePlaceholders = false,
)

private class GuideEnrichedChannelPagingSource(
    private val delegate: PagingSource<Int, ActiveChannelBrowseRow>,
    private val profileId: String,
    private val guideRepository: EpgGuideRepository,
    private val nowEpochMillis: () -> Long,
) : PagingSource<Int, ChannelBrowseItem>() {
    private val boundaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val boundaryLock = Any()
    private var scheduledBoundary: Long? = null
    private var boundaryJob: Job? = null

    init {
        delegate.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback {
            delegate.invalidate()
            boundaryScope.cancel()
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ChannelBrowseItem>): Int? =
        state.anchorPosition?.let { anchor ->
            (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0)
        }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ChannelBrowseItem> =
        when (val loaded = delegate.load(params)) {
            is LoadResult.Error -> LoadResult.Error(loaded.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
            is LoadResult.Page -> {
                val guideByChannelId = guideFor(loaded.data)
                LoadResult.Page(
                    data = loaded.data.map { row -> row.toModel(guideByChannelId[row.channelId]) },
                    prevKey = loaded.prevKey,
                    nextKey = loaded.nextKey,
                    itemsBefore = loaded.itemsBefore,
                    itemsAfter = loaded.itemsAfter,
                )
            }
        }

    private suspend fun guideFor(rows: List<ActiveChannelBrowseRow>): Map<String, ChannelNowNext> {
        if (rows.isEmpty()) return emptyMap()
        val now = nowEpochMillis()
        val guide = try {
            guideRepository.getNowNext(
                NowNextQuery(
                    profileId = profileId,
                    canonicalChannelIds = rows.map(ActiveChannelBrowseRow::channelId),
                    nowEpochMillis = now,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyMap()
        }
        scheduleBoundaryRefresh(guide, now)
        return guide.associateBy(ChannelNowNext::canonicalChannelId)
    }

    private fun scheduleBoundaryRefresh(guide: List<ChannelNowNext>, now: Long) {
        val boundary = guide.asSequence()
            .mapNotNull(ChannelNowNext::nextBoundaryEpochMillis)
            .filter { it > now }
            .minOrNull()
            ?: return
        synchronized(boundaryLock) {
            val current = scheduledBoundary
            if (current != null && current <= boundary) return
            boundaryJob?.cancel()
            scheduledBoundary = boundary
            boundaryJob = boundaryScope.launch {
                delay((boundary - nowEpochMillis()).coerceAtLeast(0L))
                invalidate()
            }
        }
    }
}

private fun ActiveChannelBrowseRow.toModel(guide: ChannelNowNext?): ChannelBrowseItem =
    ChannelBrowseItem(
        channelId = channelId,
        displayName = displayName,
        channelNumber = channelNumber,
        groupTitle = groupTitle,
        isFavorite = isFavorite,
        isCurrentPlayback = false,
        currentProgrammeTitle = guide?.current?.title,
        currentProgrammeEndEpochMillis = guide?.current?.endEpochMillis,
        nextProgrammeTitle = guide?.next?.title,
        nextProgrammeStartEpochMillis = guide?.next?.startEpochMillis,
        variantCount = variantCount,
        guideState = guide?.state ?: GuideProjectionState.NO_GUIDE,
    )

private fun ActiveChannelManagementRow.toModel(): ChannelManagementItem =
    ChannelManagementItem(
        channelId = channelId,
        canonicalDisplayName = canonicalDisplayName,
        effectiveDisplayName = effectiveDisplayName,
        defaultChannelNumber = defaultChannelNumber,
        customChannelNumber = customChannelNumber,
        effectiveChannelNumber = effectiveChannelNumber,
        isFavorite = isFavorite,
        isHidden = isHidden,
        variantCount = variantCount,
    )

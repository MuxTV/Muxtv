package app.muxtv

import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoGuideEpgGuideRepository : EpgGuideRepository {
    override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> =
        query.canonicalChannelIds.map { channelId ->
            ChannelNowNext(
                canonicalChannelId = channelId,
                state = GuideProjectionState.NO_GUIDE,
                current = null,
                next = null,
                nextBoundaryEpochMillis = null,
            )
        }

    override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
}

internal class StaticNowNextEpgGuideRepository(
    private val channelId: String,
    private val currentTitle: String,
    private val nextTitle: String,
) : EpgGuideRepository {
    override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> =
        query.canonicalChannelIds.map { requestedId ->
            if (requestedId == channelId) {
                ChannelNowNext(
                    canonicalChannelId = requestedId,
                    state = GuideProjectionState.READY,
                    current = GuideProgramme(
                        startEpochMillis = query.nowEpochMillis.coerceAtLeast(1),
                        endEpochMillis = query.nowEpochMillis + 60_000,
                        title = currentTitle,
                    ),
                    next = GuideProgramme(
                        startEpochMillis = query.nowEpochMillis + 60_000,
                        endEpochMillis = query.nowEpochMillis + 120_000,
                        title = nextTitle,
                    ),
                    nextBoundaryEpochMillis = query.nowEpochMillis + 60_000,
                )
            } else {
                ChannelNowNext(
                    canonicalChannelId = requestedId,
                    state = GuideProjectionState.NO_GUIDE,
                    current = null,
                    next = null,
                    nextBoundaryEpochMillis = null,
                )
            }
        }

    override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
}

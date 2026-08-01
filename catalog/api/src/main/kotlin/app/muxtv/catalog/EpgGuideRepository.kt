package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

class NowNextQuery(
    val profileId: String,
    canonicalChannelIds: List<String>,
    val nowEpochMillis: Long,
) {
    val canonicalChannelIds: List<String> = canonicalChannelIds.toList()

    init {
        require(profileId.isNotBlank())
        require(nowEpochMillis >= 0)
        require(this.canonicalChannelIds.size <= MAX_CHANNEL_IDS)
        require(this.canonicalChannelIds.none(String::isBlank))
        require(this.canonicalChannelIds.distinct().size == this.canonicalChannelIds.size)
    }

    override fun toString(): String =
        "NowNextQuery(profileId=<redacted>, channelCount=${canonicalChannelIds.size}, " +
            "nowEpochMillis=$nowEpochMillis)"

    companion object {
        const val MAX_CHANNEL_IDS = 200
    }
}

data class GuideProgramme(
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val title: String?,
) {
    init {
        require(startEpochMillis >= 0)
        require(endEpochMillis == null || endEpochMillis > startEpochMillis)
    }

    override fun toString(): String =
        "GuideProgramme(startEpochMillis=$startEpochMillis, endEpochMillis=$endEpochMillis, " +
            "titlePresent=${title != null})"
}

enum class GuideProjectionState {
    READY,
    NO_GUIDE,
    SOURCE_CONFLICT,
}

data class ChannelNowNext(
    val canonicalChannelId: String,
    val state: GuideProjectionState,
    val current: GuideProgramme?,
    val next: GuideProgramme?,
    val nextBoundaryEpochMillis: Long?,
) {
    init {
        require(canonicalChannelId.isNotBlank())
        require(nextBoundaryEpochMillis == null || nextBoundaryEpochMillis >= 0)
        if (state != GuideProjectionState.READY) {
            require(current == null)
            require(next == null)
            require(nextBoundaryEpochMillis == null)
        }
    }

    override fun toString(): String =
        "ChannelNowNext(canonicalChannelId=<redacted>, state=$state, " +
            "currentPresent=${current != null}, nextPresent=${next != null}, " +
            "nextBoundaryPresent=${nextBoundaryEpochMillis != null})"
}

interface EpgGuideRepository {
    suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext>

    fun observeDataChanges(): Flow<Unit>
}

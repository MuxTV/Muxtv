package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

data class GuideChannelCursor(
    val channelNumber: Int?,
    val displayName: String,
    val canonicalChannelId: String,
) {
    init {
        require(channelNumber == null || channelNumber >= 0)
        require(displayName.isNotBlank())
        require(canonicalChannelId.isNotBlank())
    }

    override fun toString(): String =
        "GuideChannelCursor(channelNumberPresent=${channelNumber != null}, " +
            "displayName=<redacted>, canonicalChannelId=<redacted>)"
}

data class GuideChannelWindowQuery(
    val profileId: String,
    val after: GuideChannelCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(profileId.isNotBlank())
        require(limit in 1..MAX_LIMIT)
    }

    override fun toString(): String =
        "GuideChannelWindowQuery(profileId=<redacted>, hasCursor=${after != null}, limit=$limit)"

    companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 50
    }
}

class GuideChannelWindow(
    channels: List<PlayableChannelSummary>,
    val nextCursor: GuideChannelCursor?,
    val isTruncated: Boolean,
) {
    val channels: List<PlayableChannelSummary> = channels.toList()

    init {
        require(this.channels.size <= GuideChannelWindowQuery.MAX_LIMIT)
        require(isTruncated == (nextCursor != null)) {
            "A truncated Guide channel window requires a continuation cursor, " +
                "and a complete window must not expose one."
        }
        require(!isTruncated || this.channels.isNotEmpty()) {
            "A truncated Guide channel window must contain at least one channel."
        }
    }

    override fun toString(): String =
        "GuideChannelWindow(channelCount=${channels.size}, " +
            "hasNextCursor=${nextCursor != null}, isTruncated=$isTruncated)"
}

class GuideProgrammeWindowQuery(
    val profileId: String,
    canonicalChannelIds: List<String>,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val limit: Int = DEFAULT_LIMIT,
) {
    val canonicalChannelIds: List<String> = canonicalChannelIds.toList()

    init {
        require(profileId.isNotBlank())
        require(this.canonicalChannelIds.size <= MAX_CHANNEL_IDS)
        require(this.canonicalChannelIds.none(String::isBlank))
        require(this.canonicalChannelIds.distinct().size == this.canonicalChannelIds.size)
        require(fromEpochMillis >= 0)
        require(toEpochMillis > fromEpochMillis)
        require(toEpochMillis - fromEpochMillis <= MAX_SPAN_MILLIS)
        require(limit in 1..MAX_LIMIT)
    }

    override fun toString(): String =
        "GuideProgrammeWindowQuery(profileId=<redacted>, " +
            "channelCount=${canonicalChannelIds.size}, fromEpochMillis=$fromEpochMillis, " +
            "toEpochMillis=$toEpochMillis, limit=$limit)"

    companion object {
        const val DEFAULT_LIMIT = 1_000
        const val MAX_LIMIT = 2_000
        const val MAX_CHANNEL_IDS = GuideChannelWindowQuery.MAX_LIMIT
        const val MAX_SPAN_MILLIS = 12L * 60L * 60L * 1_000L
    }
}

data class GuideProgrammeKey(
    val epgSourceId: String,
    val epgRevisionNumber: Long,
    val sequenceNumber: Long,
) {
    init {
        require(epgSourceId.isNotBlank())
        require(epgRevisionNumber > 0)
        require(sequenceNumber >= 0)
    }

    override fun toString(): String =
        "GuideProgrammeKey(epgSourceId=<redacted>, " +
            "epgRevisionNumber=$epgRevisionNumber, sequenceNumber=$sequenceNumber)"
}

data class GuideProgrammeCell(
    val key: GuideProgrammeKey,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val title: String?,
) {
    init {
        require(startEpochMillis >= 0)
        require(endEpochMillis > startEpochMillis)
    }

    override fun toString(): String =
        "GuideProgrammeCell(key=<redacted>, startEpochMillis=$startEpochMillis, " +
            "endEpochMillis=$endEpochMillis, titlePresent=${!title.isNullOrBlank()})"
}

class ChannelGuideProgrammeWindow(
    val canonicalChannelId: String,
    val state: GuideProjectionState,
    programmes: List<GuideProgrammeCell>,
) {
    val programmes: List<GuideProgrammeCell> = programmes.toList()

    init {
        require(canonicalChannelId.isNotBlank())
        require(state == GuideProjectionState.READY || this.programmes.isEmpty()) {
            "Only READY Guide channel windows may carry programme payload."
        }
    }

    override fun toString(): String =
        "ChannelGuideProgrammeWindow(canonicalChannelId=<redacted>, state=$state, " +
            "programmeCount=${programmes.size})"
}

class GuideProgrammeWindow(
    channels: List<ChannelGuideProgrammeWindow>,
    val isTruncated: Boolean,
) {
    val channels: List<ChannelGuideProgrammeWindow> = channels.toList()

    init {
        require(
            this.channels.map(ChannelGuideProgrammeWindow::canonicalChannelId).distinct().size ==
                this.channels.size,
        ) { "Guide programme window must contain at most one result per canonical channel." }
    }

    override fun toString(): String =
        "GuideProgrammeWindow(channelCount=${channels.size}, isTruncated=$isTruncated)"
}

interface GuideWindowRepository {
    suspend fun getChannelWindow(query: GuideChannelWindowQuery): GuideChannelWindow

    suspend fun getProgrammeWindow(query: GuideProgrammeWindowQuery): GuideProgrammeWindow

    fun observeDataChanges(): Flow<Unit>
}

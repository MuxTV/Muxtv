package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

data class RecentChannelsQuery(
    val profileId: String,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(profileId.isNotBlank())
        require(limit in 1..MAX_LIMIT)
    }

    override fun toString(): String =
        "RecentChannelsQuery(profileId=<redacted>, limit=$limit)"

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

data class RecentChannel(
    val channel: PlayableChannelSummary,
    val lastSuccessfulPlaybackAtEpochMillis: Long,
) {
    init {
        require(lastSuccessfulPlaybackAtEpochMillis >= 0L)
    }

    override fun toString(): String =
        "RecentChannel(channelId=<redacted>, lastSuccessfulPlaybackAtEpochMillis=" +
            "$lastSuccessfulPlaybackAtEpochMillis)"
}

enum class RecentChannelWriteResult {
    Applied,
    IgnoredOlderOrDuplicate,
    ProfileUnavailable,
}

interface RecentChannelsRepository {
    fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>>

    suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult
}

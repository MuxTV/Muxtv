package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

class ChannelSearchQuery(
    val profileId: String,
    text: String,
    val nowEpochMillis: Long,
    val limit: Int = DEFAULT_LIMIT,
) {
    val normalizedText: String = normalizeSearchText(text)

    init {
        require(profileId.isNotBlank())
        require(nowEpochMillis >= 0)
        require(limit in 1..MAX_LIMIT)
    }

    override fun toString(): String =
        "ChannelSearchQuery(profileId=<redacted>, hasText=${normalizedText.isNotEmpty()}, " +
            "lengthBucket=${normalizedText.lengthBucket()}, nowEpochMillis=$nowEpochMillis, limit=$limit)"

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
        const val MAX_TOKENS = 6
    }
}

data class ChannelSearchResult(
    val channel: PlayableChannelSummary,
    val currentProgrammeTitle: String?,
) {
    override fun toString(): String =
        "ChannelSearchResult(channelId=<redacted>, currentProgrammePresent=${currentProgrammeTitle != null})"
}

data class ChannelSearchSnapshot(
    val results: List<ChannelSearchResult>,
    val isTruncated: Boolean,
    val nextBoundaryEpochMillis: Long?,
) {
    init {
        require(results.size <= ChannelSearchQuery.MAX_LIMIT)
        require(nextBoundaryEpochMillis == null || nextBoundaryEpochMillis >= 0)
    }

    override fun toString(): String =
        "ChannelSearchSnapshot(resultCount=${results.size}, isTruncated=$isTruncated, " +
            "nextBoundaryPresent=${nextBoundaryEpochMillis != null})"

    companion object {
        val EMPTY = ChannelSearchSnapshot(
            results = emptyList(),
            isTruncated = false,
            nextBoundaryEpochMillis = null,
        )
    }
}

interface ChannelSearchRepository {
    fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot>
}

private fun normalizeSearchText(value: String): String {
    if (value.isEmpty()) return ""

    return buildString(value.length) {
        var pendingSpace = false
        value.forEach { character ->
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }.trimEnd()
}

private fun String.lengthBucket(): String = when (length) {
    0 -> "0"
    in 1..3 -> "1-3"
    in 4..7 -> "4-7"
    in 8..15 -> "8-15"
    else -> "16+"
}

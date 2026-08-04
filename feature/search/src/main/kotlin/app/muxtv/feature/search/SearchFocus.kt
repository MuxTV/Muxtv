package app.muxtv.feature.search

/**
 * Secret-free focus bookmark for the bounded Search result list.
 *
 * Canonical channel identity is authoritative. Position and offset are deterministic fallbacks
 * only when the previously focused result is no longer present for the current query.
 */
internal data class SearchFocusAnchor(
    val channelId: String,
    val previousIndex: Int,
    val scrollOffset: Int,
) {
    init {
        require(channelId.isNotBlank())
        require(previousIndex >= 0)
        require(scrollOffset >= 0)
    }
}

internal data class SearchFocusTarget(
    val channelId: String,
    val index: Int,
    val scrollOffset: Int,
)

internal fun SearchFocusAnchor.resolveAgainst(channelIds: List<String>): SearchFocusTarget? {
    if (channelIds.isEmpty()) return null

    val exactIndex = channelIds.indexOf(channelId)
    val targetIndex = when {
        exactIndex >= 0 -> exactIndex
        previousIndex > 0 -> minOf(previousIndex - 1, channelIds.lastIndex)
        previousIndex <= channelIds.lastIndex -> previousIndex
        else -> 0
    }

    return SearchFocusTarget(
        channelId = channelIds[targetIndex],
        index = targetIndex,
        scrollOffset = scrollOffset,
    )
}

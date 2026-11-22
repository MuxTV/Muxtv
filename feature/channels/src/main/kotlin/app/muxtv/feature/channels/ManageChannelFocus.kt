package app.muxtv.feature.channels

/**
 * Returns a focusable target from the currently loaded Paging window.
 *
 * Unlike full-list [FocusAnchor.resolveAgainst], a management mutation must
 * not wait for append.endOfPaginationReached: an IPTV catalog may contain
 * thousands of rows while the adjacent row is already on screen.
 *
 * A row being removed must disappear from the loaded snapshot BEFORE focus
 * moves; this prevents a stale pre-mutation frame from stealing TV focus.
 * Missing/unloaded entries are never selected as focus destinations.
 */
internal fun FocusAnchor.resolveForManagement(
    loadedKeys: List<String?>,
    waitForAnchorRemoval: Boolean,
): FocusTarget? {
    val exactIndex = loadedKeys.indexOf(itemKey)
    if (exactIndex >= 0) {
        if (waitForAnchorRemoval) return null
        return FocusTarget(itemKey, exactIndex, scrollOffset)
    }

    val preferredIndex = if (previousIndex > 0) previousIndex - 1 else 0
    val previousLoaded = loadedKeys.indices.reversed().firstOrNull { index ->
        index <= preferredIndex && !loadedKeys[index].isNullOrBlank()
    }
    val nextLoaded = loadedKeys.indices.firstOrNull { index ->
        index > preferredIndex && !loadedKeys[index].isNullOrBlank()
    }
    val resolvedIndex = previousLoaded ?: nextLoaded ?: return null
    return FocusTarget(
        itemKey = requireNotNull(loadedKeys[resolvedIndex]),
        index = resolvedIndex,
        scrollOffset = scrollOffset,
    )
}

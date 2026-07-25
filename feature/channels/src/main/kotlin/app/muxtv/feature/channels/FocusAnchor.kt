package app.muxtv.feature.channels

/**
 * Secret-free saved state required to restore real TV focus after returning to a list.
 *
 * [previousIndex] and [scrollOffset] are positional fallbacks only. [itemKey] remains the primary
 * identity and must be a stable catalog key rather than a stream locator.
 */
internal data class FocusAnchor(
    val itemKey: String,
    val previousIndex: Int,
    val scrollOffset: Int,
) {
    init {
        require(itemKey.isNotBlank())
        require(previousIndex >= 0)
        require(scrollOffset >= 0)
    }
}

internal data class FocusTarget(
    val itemKey: String,
    val index: Int,
    val scrollOffset: Int,
)

/**
 * Resolves focus deterministically without assuming that the previous catalog order still exists.
 *
 * Policy:
 * 1. exact stable key;
 * 2. nearest preceding position when the key disappeared;
 * 3. previous position for an item that was first;
 * 4. no target for an empty list.
 */
internal fun FocusAnchor.resolveAgainst(itemKeys: List<String>): FocusTarget? {
    if (itemKeys.isEmpty()) return null

    val exactIndex = itemKeys.indexOf(itemKey)
    val targetIndex = when {
        exactIndex >= 0 -> exactIndex
        previousIndex > 0 -> minOf(previousIndex - 1, itemKeys.lastIndex)
        previousIndex <= itemKeys.lastIndex -> previousIndex
        else -> 0
    }

    return FocusTarget(
        itemKey = itemKeys[targetIndex],
        index = targetIndex,
        scrollOffset = scrollOffset,
    )
}

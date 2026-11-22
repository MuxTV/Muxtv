package app.muxtv.feature.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManageChannelFocusTest {
    @Test
    fun removedRowUsesLoadedPreviousNeighborWithoutLastPage() {
        // 64 loaded rows, with more provider rows still available downstream.
        val firstPage = (1..64).map { "channel-$it" }
        val anchor = FocusAnchor("channel-65", previousIndex = 64, scrollOffset = 12)

        assertThat(anchor.resolveForManagement(firstPage, waitForAnchorRemoval = true))
            .isEqualTo(FocusTarget("channel-64", 63, 12))
    }

    @Test
    fun removalWaitsWhileOldKeyIsStillVisibleInStalePage() {
        val anchor = FocusAnchor("channel-2", previousIndex = 1, scrollOffset = 0)
        assertThat(
            anchor.resolveForManagement(
                loadedKeys = listOf("channel-1", "channel-2", "channel-3"),
                waitForAnchorRemoval = true,
            ),
        ).isNull()
    }

    @Test
    fun dismissalRestoresExactIdentityAfterReorder() {
        val anchor = FocusAnchor("channel-2", previousIndex = 1, scrollOffset = 4)
        assertThat(
            anchor.resolveForManagement(
                loadedKeys = listOf("channel-3", "channel-1", "channel-2"),
                waitForAnchorRemoval = false,
            ),
        ).isEqualTo(FocusTarget("channel-2", 2, 4))
    }

    @Test
    fun removedFirstRowFallsForwardToFirstLoadedKey() {
        val anchor = FocusAnchor("channel-1", previousIndex = 0, scrollOffset = 0)
        assertThat(
            anchor.resolveForManagement(
                loadedKeys = listOf(null, "channel-2", "channel-3"),
                waitForAnchorRemoval = true,
            ),
        ).isEqualTo(FocusTarget("channel-2", 1, 0))
    }

    @Test
    fun unloadedNeighborsAreSkippedAndEmptyWindowReturnsNull() {
        val anchor = FocusAnchor("removed", previousIndex = 4, scrollOffset = 3)
        assertThat(
            anchor.resolveForManagement(
                loadedKeys = listOf("a", null, null, null, "b"),
                waitForAnchorRemoval = true,
            ),
        ).isEqualTo(FocusTarget("a", 0, 3))
        assertThat(
            anchor.resolveForManagement(listOf(null, null), waitForAnchorRemoval = true),
        ).isNull()
    }
}

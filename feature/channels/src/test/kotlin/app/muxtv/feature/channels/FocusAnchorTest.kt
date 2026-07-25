package app.muxtv.feature.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusAnchorTest {
    @Test
    fun exactIdentityWinsAfterReorder() {
        val target = FocusAnchor(
            itemKey = "channel-b",
            previousIndex = 1,
            scrollOffset = 24,
        ).resolveAgainst(
            itemKeys = listOf("channel-c", "channel-b", "channel-a"),
        )

        assertThat(target).isEqualTo(
            FocusTarget(
                itemKey = "channel-b",
                index = 1,
                scrollOffset = 24,
            ),
        )
    }

    @Test
    fun removedIdentityUsesNearestPreviousPosition() {
        val target = FocusAnchor(
            itemKey = "channel-c",
            previousIndex = 2,
            scrollOffset = 8,
        ).resolveAgainst(
            itemKeys = listOf("channel-a", "channel-b", "channel-d"),
        )

        assertThat(target).isEqualTo(
            FocusTarget(
                itemKey = "channel-b",
                index = 1,
                scrollOffset = 8,
            ),
        )
    }

    @Test
    fun removedFirstIdentityFallsBackToFirstFocusableItem() {
        val target = FocusAnchor(
            itemKey = "channel-a",
            previousIndex = 0,
            scrollOffset = 0,
        ).resolveAgainst(
            itemKeys = listOf("channel-b", "channel-c"),
        )

        assertThat(target).isEqualTo(
            FocusTarget(
                itemKey = "channel-b",
                index = 0,
                scrollOffset = 0,
            ),
        )
    }

    @Test
    fun shrinkingListClampsNearestPreviousPositionToLastItem() {
        val target = FocusAnchor(
            itemKey = "removed",
            previousIndex = 10,
            scrollOffset = 4,
        ).resolveAgainst(
            itemKeys = listOf("channel-a", "channel-b", "channel-c"),
        )

        assertThat(target).isEqualTo(
            FocusTarget(
                itemKey = "channel-c",
                index = 2,
                scrollOffset = 4,
            ),
        )
    }

    @Test
    fun emptyListHasNoFocusTarget() {
        val target = FocusAnchor(
            itemKey = "channel-a",
            previousIndex = 0,
            scrollOffset = 0,
        ).resolveAgainst(emptyList())

        assertThat(target).isNull()
    }
}

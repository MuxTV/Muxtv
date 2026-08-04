package app.muxtv.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchFocusTest {
    @Test
    fun `exact canonical channel survives result reorder`() {
        val target = SearchFocusAnchor(
            channelId = "channel-b",
            previousIndex = 1,
            scrollOffset = 24,
        ).resolveAgainst(listOf("channel-b", "channel-a"))

        assertThat(target).isEqualTo(
            SearchFocusTarget(
                channelId = "channel-b",
                index = 0,
                scrollOffset = 24,
            ),
        )
    }

    @Test
    fun `removed result falls back to nearest previous position`() {
        val target = SearchFocusAnchor(
            channelId = "channel-c",
            previousIndex = 2,
            scrollOffset = 12,
        ).resolveAgainst(listOf("channel-a", "channel-b"))

        assertThat(target).isEqualTo(
            SearchFocusTarget(
                channelId = "channel-b",
                index = 1,
                scrollOffset = 12,
            ),
        )
    }

    @Test
    fun `removed first result falls back to new first result`() {
        val target = SearchFocusAnchor(
            channelId = "channel-a",
            previousIndex = 0,
            scrollOffset = 0,
        ).resolveAgainst(listOf("channel-b", "channel-c"))

        assertThat(target?.channelId).isEqualTo("channel-b")
        assertThat(target?.index).isEqualTo(0)
    }

    @Test
    fun `empty results have no focus target`() {
        val target = SearchFocusAnchor(
            channelId = "channel-a",
            previousIndex = 0,
            scrollOffset = 0,
        ).resolveAgainst(emptyList())

        assertThat(target).isNull()
    }
}

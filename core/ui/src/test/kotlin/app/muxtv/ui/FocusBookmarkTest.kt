package app.muxtv.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusBookmarkTest {

    @Test
    fun remember_replacesPreviousKeyWithinSameScope() {
        val bookmark = FocusBookmark<String>()

        bookmark.remember(scope = "channels", key = "channel-1")
        bookmark.remember(scope = "channels", key = "channel-2")

        assertThat(bookmark.restore("channels")).isEqualTo("channel-2")
    }

    @Test
    fun restoreValid_returnsRememberedKeyWhenItStillExists() {
        val bookmark = FocusBookmark<String>()
        bookmark.remember(scope = "channels", key = "channel-2")

        val restored = bookmark.restoreValid(scope = "channels") { key ->
            key in setOf("channel-1", "channel-2", "channel-3")
        }

        assertThat(restored).isEqualTo("channel-2")
        assertThat(bookmark.restore("channels")).isEqualTo("channel-2")
    }

    @Test
    fun restoreValid_discardsBookmarkWhenItemWasRemoved() {
        val bookmark = FocusBookmark<String>()
        bookmark.remember(scope = "channels", key = "removed-channel")

        val restored = bookmark.restoreValid(scope = "channels") { key ->
            key in setOf("channel-1", "channel-2")
        }

        assertThat(restored).isNull()
        assertThat(bookmark.restore("channels")).isNull()
    }

    @Test
    fun clear_removesOnlyRequestedScope() {
        val bookmark = FocusBookmark<String>()
        bookmark.remember(scope = "channels", key = "channel-1")
        bookmark.remember(scope = "guide", key = "programme-1")

        bookmark.clear("channels")

        assertThat(bookmark.restore("channels")).isNull()
        assertThat(bookmark.restore("guide")).isEqualTo("programme-1")
    }
}

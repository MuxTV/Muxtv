package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackFirstFrameTrackerTest {
    @Test
    fun `matching current setup publishes exactly once`() {
        var nowNanos = 1_000_000_000L
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = PlaybackFirstFrameTracker(
            elapsedRealtimeNanos = { nowNanos },
            publish = published::add,
        )
        val setupId = setupId("00000000-0000-0000-0000-000000000101")

        tracker.activate(setupId, "channel-a")
        nowNanos += 235_000_000L
        val first = tracker.onRenderedFirstFrame("channel-a")
        val duplicate = tracker.onRenderedFirstFrame("channel-a")

        assertThat(first).isEqualTo(
            PlaybackFirstFrameEvent(
                channelId = "channel-a",
                activationElapsedMillis = 235L,
            ),
        )
        assertThat(duplicate).isNull()
        assertThat(published).containsExactly(first)
    }

    @Test
    fun `stale media id cannot complete newer setup`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)

        tracker.activate(
            setupId("00000000-0000-0000-0000-000000000102"),
            "channel-a",
        )
        tracker.activate(
            setupId("00000000-0000-0000-0000-000000000103"),
            "channel-b",
        )

        assertThat(tracker.onRenderedFirstFrame("channel-a")).isNull()
        assertThat(published).isEmpty()
        assertThat(tracker.onRenderedFirstFrame("channel-b")).isNotNull()
        assertThat(published).hasSize(1)
        assertThat(published.single().channelId).isEqualTo("channel-b")
    }

    @Test
    fun `cancelled active setup cannot publish`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)
        val setupId = setupId("00000000-0000-0000-0000-000000000104")

        tracker.activate(setupId, "channel-a")
        assertThat(tracker.clear(setupId)).isTrue()

        assertThat(tracker.onRenderedFirstFrame("channel-a")).isNull()
        assertThat(published).isEmpty()
    }

    @Test
    fun `stale clear cannot remove newer setup`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)
        val first = setupId("00000000-0000-0000-0000-000000000105")
        val second = setupId("00000000-0000-0000-0000-000000000106")

        tracker.activate(first, "channel-a")
        tracker.activate(second, "channel-b")

        assertThat(tracker.clear(first)).isFalse()
        assertThat(tracker.onRenderedFirstFrame("channel-b")).isNotNull()
        assertThat(published).hasSize(1)
    }

    @Test
    fun `missing or mismatched media id is rejected`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)

        tracker.activate(
            setupId("00000000-0000-0000-0000-000000000107"),
            "channel-a",
        )

        assertThat(tracker.onRenderedFirstFrame(null)).isNull()
        assertThat(tracker.onRenderedFirstFrame("channel-other")).isNull()
        assertThat(published).isEmpty()
    }

    @Test
    fun `negative clock movement is clamped and diagnostics redact channel`() {
        var nowNanos = 2_000_000L
        val tracker = PlaybackFirstFrameTracker(
            elapsedRealtimeNanos = { nowNanos },
            publish = {},
        )

        tracker.activate(
            setupId("00000000-0000-0000-0000-000000000108"),
            "private-channel-id",
        )
        nowNanos = 1_000_000L
        val event = requireNotNull(tracker.onRenderedFirstFrame("private-channel-id"))

        assertThat(event.activationElapsedMillis).isEqualTo(0L)
        assertThat(event.toString()).doesNotContain("private-channel-id")
        assertThat(event.toString()).contains("activationElapsedMillis=0")
    }

    private fun tracker(
        published: MutableList<PlaybackFirstFrameEvent>,
    ): PlaybackFirstFrameTracker = PlaybackFirstFrameTracker(
        elapsedRealtimeNanos = { 1_000_000_000L },
        publish = published::add,
    )

    private fun setupId(raw: String): PlaybackSetupId =
        requireNotNull(PlaybackSetupId.parse(raw))
}

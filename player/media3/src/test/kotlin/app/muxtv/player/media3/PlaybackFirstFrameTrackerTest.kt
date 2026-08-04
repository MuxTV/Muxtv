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

        tracker.activate(setupId, PROFILE_ID, "channel-a")
        nowNanos += 235_000_000L
        val first = tracker.onRenderedFirstFrame(setupId, "channel-a")
        val duplicate = tracker.onRenderedFirstFrame(setupId, "channel-a")

        assertThat(first).isEqualTo(
            PlaybackFirstFrameEvent(
                profileId = PROFILE_ID,
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
        val first = setupId("00000000-0000-0000-0000-000000000102")
        val second = setupId("00000000-0000-0000-0000-000000000103")

        tracker.activate(first, PROFILE_ID, "channel-a")
        tracker.activate(second, PROFILE_ID, "channel-b")

        assertThat(tracker.onRenderedFirstFrame(second, "channel-a")).isNull()
        assertThat(published).isEmpty()
        assertThat(tracker.onRenderedFirstFrame(second, "channel-b")).isNotNull()
        assertThat(published).hasSize(1)
        assertThat(published.single().profileId).isEqualTo(PROFILE_ID)
        assertThat(published.single().channelId).isEqualTo("channel-b")
    }

    @Test
    fun `stale callback for the same channel cannot complete newer setup`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)
        val first = setupId("00000000-0000-0000-0000-000000000109")
        val second = setupId("00000000-0000-0000-0000-000000000110")

        tracker.activate(first, PROFILE_ID, "channel-a")
        tracker.activate(second, PROFILE_ID, "channel-a")

        assertThat(tracker.onRenderedFirstFrame(first, "channel-a")).isNull()
        assertThat(published).isEmpty()
        assertThat(tracker.onRenderedFirstFrame(second, "channel-a")).isNotNull()
        assertThat(published).hasSize(1)
    }

    @Test
    fun `cancelled active setup cannot publish`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)
        val setupId = setupId("00000000-0000-0000-0000-000000000104")

        tracker.activate(setupId, PROFILE_ID, "channel-a")
        assertThat(tracker.clear(setupId)).isTrue()

        assertThat(tracker.onRenderedFirstFrame(setupId, "channel-a")).isNull()
        assertThat(published).isEmpty()
    }

    @Test
    fun `stale clear cannot remove newer setup`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)
        val first = setupId("00000000-0000-0000-0000-000000000105")
        val second = setupId("00000000-0000-0000-0000-000000000106")

        tracker.activate(first, PROFILE_ID, "channel-a")
        tracker.activate(second, PROFILE_ID, "channel-b")

        assertThat(tracker.clear(first)).isFalse()
        assertThat(tracker.onRenderedFirstFrame(second, "channel-b")).isNotNull()
        assertThat(published).hasSize(1)
    }

    @Test
    fun `missing or mismatched media id is rejected`() {
        val published = mutableListOf<PlaybackFirstFrameEvent>()
        val tracker = tracker(published)
        val setupId = setupId("00000000-0000-0000-0000-000000000107")

        tracker.activate(setupId, PROFILE_ID, "channel-a")

        assertThat(tracker.onRenderedFirstFrame(setupId, null)).isNull()
        assertThat(tracker.onRenderedFirstFrame(setupId, "channel-other")).isNull()
        assertThat(published).isEmpty()
    }

    @Test
    fun `negative clock movement is clamped and diagnostics redact identities`() {
        var nowNanos = 2_000_000L
        val tracker = PlaybackFirstFrameTracker(
            elapsedRealtimeNanos = { nowNanos },
            publish = {},
        )
        val setupId = setupId("00000000-0000-0000-0000-000000000108")

        tracker.activate(
            setupId,
            "private-profile-id",
            "private-channel-id",
        )
        nowNanos = 1_000_000L
        val event = requireNotNull(
            tracker.onRenderedFirstFrame(setupId, "private-channel-id"),
        )

        assertThat(event.activationElapsedMillis).isEqualTo(0L)
        assertThat(event.toString()).doesNotContain("private-profile-id")
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

    private companion object {
        const val PROFILE_ID = "profile-main"
    }
}

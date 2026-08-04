package app.muxtv.di

import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.player.media3.PlaybackFirstFrameEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class RecentPlaybackObserverTest {
    @Test
    fun `first frame records exact identity with wall clock from Recent boundary`() {
        val repository = RecordingRecentRepository()
        val observer = RecentPlaybackObserver(
            repository = repository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowEpochMillis = { 42_000L },
        )

        observer.onFirstFrame(
            PlaybackFirstFrameEvent(
                profileId = "profile-main",
                channelId = "channel-a",
                activationElapsedMillis = 275L,
            ),
        )

        assertThat(repository.records).containsExactly(
            Record("profile-main", "channel-a", 42_000L),
        )
    }

    @Test
    fun `persistence failure is isolated from playback callback`() {
        val repository = RecordingRecentRepository(failWrites = true)
        val observer = RecentPlaybackObserver(
            repository = repository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowEpochMillis = { 42_000L },
        )

        observer.onFirstFrame(
            PlaybackFirstFrameEvent(
                profileId = "profile-main",
                channelId = "channel-a",
                activationElapsedMillis = 275L,
            ),
        )

        assertThat(repository.records).containsExactly(
            Record("profile-main", "channel-a", 42_000L),
        )
    }

    private class RecordingRecentRepository(
        private val failWrites: Boolean = false,
    ) : RecentChannelsRepository {
        val records = mutableListOf<Record>()

        override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> = emptyFlow()

        override suspend fun recordSuccessfulPlayback(
            profileId: String,
            channelId: String,
            successfulAtEpochMillis: Long,
        ): RecentChannelWriteResult {
            records += Record(profileId, channelId, successfulAtEpochMillis)
            if (failWrites) error("synthetic persistence failure")
            return RecentChannelWriteResult.Applied
        }
    }

    private data class Record(
        val profileId: String,
        val channelId: String,
        val successfulAtEpochMillis: Long,
    )
}

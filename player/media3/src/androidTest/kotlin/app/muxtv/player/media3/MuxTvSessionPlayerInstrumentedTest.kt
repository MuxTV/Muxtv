package app.muxtv.player.media3

import android.content.Context
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@AndroidXOptIn(UnstableApi::class)
class MuxTvSessionPlayerInstrumentedTest {
    @Test
    fun commandAndEventFiltering_usesRealAndroidMedia3Semantics() {
        val sourceCommands = Player.Commands.Builder()
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .build()

        val filteredCommands = filteredMuxTvSessionCommands(sourceCommands)

        assertThat(filteredCommands.contains(Player.COMMAND_SEEK_BACK)).isTrue()
        assertThat(filteredCommands.contains(Player.COMMAND_SEEK_FORWARD)).isTrue()
        assertThat(filteredCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)).isTrue()
        blockedMuxTvSessionSeekCommands.forEach { command ->
            assertThat(filteredCommands.contains(command)).isFalse()
        }

        val sourceEvents = Player.Events(
            FlagSet.Builder()
                .add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                .build(),
        )
        val filteredEvents = filteredMuxTvSessionEvents(sourceEvents)

        assertThat(filteredEvents.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)).isFalse()
        assertThat(filteredEvents.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)).isTrue()
    }

    @Test
    fun standardSeekSurface_emitsServiceIntents_withoutCallingDelegateSeekMethods() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val exoPlayer = ExoPlayer.Builder(context).build()
            try {
                val delegateSeekCalls = mutableListOf<String>()
                val recordingDelegate = object : ForwardingPlayer(exoPlayer) {
                    override fun seekBack() {
                        delegateSeekCalls += "back"
                    }

                    override fun seekForward() {
                        delegateSeekCalls += "forward"
                    }

                    override fun seekTo(positionMs: Long) {
                        delegateSeekCalls += "absolute:$positionMs"
                    }

                    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                        delegateSeekCalls += "indexed:$mediaItemIndex:$positionMs"
                    }

                    override fun seekToDefaultPosition() {
                        delegateSeekCalls += "default"
                    }

                    override fun seekToDefaultPosition(mediaItemIndex: Int) {
                        delegateSeekCalls += "default-indexed:$mediaItemIndex"
                    }

                    override fun seekToNext() {
                        delegateSeekCalls += "next"
                    }

                    override fun seekToNextMediaItem() {
                        delegateSeekCalls += "next-item"
                    }

                    override fun seekToPrevious() {
                        delegateSeekCalls += "previous"
                    }

                    override fun seekToPreviousMediaItem() {
                        delegateSeekCalls += "previous-item"
                    }
                }
                val intents = mutableListOf<MuxTvSessionSeekIntent>()
                val sessionPlayer = MuxTvSessionPlayer(recordingDelegate, intents::add)

                sessionPlayer.seekBack()
                sessionPlayer.seekForward()
                sessionPlayer.seekTo(42_000L)
                sessionPlayer.seekToDefaultPosition()
                sessionPlayer.seekToNext()
                sessionPlayer.seekToPrevious()

                assertThat(intents).containsExactly(
                    MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_BACKWARD),
                    MuxTvSessionSeekIntent.Relative(PlaybackSeekPolicy.DIRECTION_FORWARD),
                    MuxTvSessionSeekIntent.Absolute(42_000L),
                ).inOrder()
                assertThat(delegateSeekCalls).isEmpty()
                blockedMuxTvSessionSeekCommands.forEach { command ->
                    assertThat(sessionPlayer.isCommandAvailable(command)).isFalse()
                }
            } finally {
                exoPlayer.release()
            }
        }
    }
}

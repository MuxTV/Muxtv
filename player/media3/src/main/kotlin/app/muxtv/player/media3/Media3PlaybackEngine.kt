package app.muxtv.player.media3

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.muxtv.player.PlaybackEngine
import app.muxtv.player.PlaybackRequest
import app.muxtv.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Media3PlaybackEngine(
    context: Context,
) : PlaybackEngine, Player.Listener {
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        player.addListener(this)
    }

    override suspend fun prepare(request: PlaybackRequest) {
        mutableState.value = PlaybackState.Preparing
        player.setMediaItem(MediaItem.fromUri(request.locator))
        player.prepare()
    }

    override suspend fun play() {
        player.play()
    }

    override suspend fun pause() {
        player.pause()
        mutableState.value = PlaybackState.Paused
    }

    override suspend fun stop() {
        player.stop()
        player.clearMediaItems()
        mutableState.value = PlaybackState.Stopped
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        mutableState.value = when (playbackState) {
            Player.STATE_BUFFERING -> PlaybackState.Preparing
            Player.STATE_READY -> if (player.playWhenReady) PlaybackState.Playing else PlaybackState.Paused
            Player.STATE_ENDED -> PlaybackState.Stopped
            else -> mutableState.value
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (player.playbackState == Player.STATE_READY) {
            mutableState.value = if (playWhenReady) PlaybackState.Playing else PlaybackState.Paused
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        mutableState.value = PlaybackState.Failed(Media3ErrorMapper.fromException(error))
    }

    fun release() {
        player.removeListener(this)
        player.release()
        mutableState.value = PlaybackState.Stopped
    }
}

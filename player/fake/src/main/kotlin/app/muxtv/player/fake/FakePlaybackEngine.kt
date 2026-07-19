package app.muxtv.player.fake

import app.muxtv.player.PlaybackEngine
import app.muxtv.player.PlaybackRequest
import app.muxtv.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakePlaybackEngine : PlaybackEngine {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    var lastRequest: PlaybackRequest? = null
        private set

    override suspend fun prepare(request: PlaybackRequest) {
        lastRequest = request
        mutableState.value = PlaybackState.Preparing
    }

    override suspend fun play() {
        mutableState.value = PlaybackState.Playing
    }

    override suspend fun pause() {
        mutableState.value = PlaybackState.Paused
    }

    override suspend fun stop() {
        mutableState.value = PlaybackState.Stopped
    }

    fun emitFirstFrame() {
        mutableState.value = PlaybackState.Playing
    }
}

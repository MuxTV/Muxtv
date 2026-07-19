package app.muxtv.player

import kotlinx.coroutines.flow.StateFlow

interface PlaybackEngine {
    val state: StateFlow<PlaybackState>

    suspend fun prepare(request: PlaybackRequest)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
}

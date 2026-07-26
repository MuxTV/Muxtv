package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@AndroidXOptIn(UnstableApi::class)
class DebugDisconnectableMediaSessionService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createSession()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = if (::mediaSession.isInitialized) mediaSession else null

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        releaseSession()
        super.onDestroy()
    }

    private fun restartSession() {
        releaseSession()
        createSession()
    }

    private fun createSession() {
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player)
            .setId(DEBUG_SESSION_ID)
            .build()
    }

    private fun releaseSession() {
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
        if (::player.isInitialized) {
            player.release()
        }
    }

    companion object {
        private const val DEBUG_SESSION_ID = "muxtv-debug-disconnectable-playback"

        @Volatile
        private var activeInstance: DebugDisconnectableMediaSessionService? = null

        fun restartActiveSessionForTest() {
            checkNotNull(activeInstance) { "Debug disconnectable session service is not active." }
                .restartSession()
        }
    }
}

package app.muxtv.player.media3

import android.os.Bundle
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.muxtv.network.MuxTvHttpClients
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
@AndroidXOptIn(UnstableApi::class)
class MuxTvPlaybackService : MediaSessionService() {
    @Inject
    lateinit var httpClients: MuxTvHttpClients

    private lateinit var mediaSourceFactory: PlaybackMediaSourceFactory
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()

        mediaSourceFactory = PlaybackMediaSourceFactory(
            context = this,
            httpClients = httpClients,
        )
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = if (::mediaSession.isInitialized) mediaSession else null

    override fun onDestroy() {
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
        if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }

    private fun install(request: PlaybackSessionRequest) {
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(mediaSourceFactory.create(request))
        player.prepare()
        player.play()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val baseResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            if (controller.packageName != packageName) return baseResult

            return MediaSession.ConnectionResult.accept(
                baseResult.availableSessionCommands
                    .buildUpon()
                    .add(MuxTvPlaybackSessionContract.setPlaybackRequestCommand)
                    .build(),
                baseResult.availablePlayerCommands,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction !=
                MuxTvPlaybackSessionContract.ACTION_SET_PLAYBACK_REQUEST
            ) {
                return Futures.immediateFuture(MuxTvPlaybackSessionContract.notSupported())
            }
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(MuxTvPlaybackSessionContract.permissionDenied())
            }

            val request = PlaybackSessionRequest.fromBundle(args)
                ?: return Futures.immediateFuture(MuxTvPlaybackSessionContract.badValue())
            install(request)
            return Futures.immediateFuture(MuxTvPlaybackSessionContract.success())
        }
    }

    private companion object {
        const val SESSION_ID = "muxtv-main-playback"
    }
}

package app.muxtv.player.media3

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class MuxTvPlaybackService : MediaSessionService() {
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()

        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
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
        httpDataSourceFactory.setDefaultRequestProperties(request.requestHeaders)

        val metadata = MediaMetadata.Builder().apply {
            request.displayName?.let(::setTitle)
            request.artworkUri?.let { setArtworkUri(Uri.parse(it)) }
        }.build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(request.mediaId)
            .setUri(Uri.parse(request.locator))
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem)
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

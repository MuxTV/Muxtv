package app.muxtv.player.media3

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class MuxTvMediaControllerConnector(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var controllerFuture: ListenableFuture<MediaController>? = null

    @Synchronized
    fun connect(): ListenableFuture<MediaController> {
        controllerFuture?.let { return it }

        val token = SessionToken(
            applicationContext,
            ComponentName(applicationContext, MuxTvPlaybackService::class.java),
        )
        return MediaController.Builder(applicationContext, token)
            .buildAsync()
            .also { controllerFuture = it }
    }

    fun sendPlaybackRequest(
        controller: MediaController,
        request: PlaybackSessionRequest,
    ): ListenableFuture<SessionResult> {
        val command = MuxTvPlaybackSessionContract.setPlaybackRequestCommand
        if (!controller.isSessionCommandAvailable(command)) {
            return Futures.immediateFuture(MuxTvPlaybackSessionContract.notSupported())
        }
        return controller.sendCustomCommand(command, request.toBundle())
    }

    @Synchronized
    fun release() {
        val future = controllerFuture ?: return
        controllerFuture = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            MediaController.releaseFuture(future)
        } else {
            mainHandler.post { MediaController.releaseFuture(future) }
        }
    }
}

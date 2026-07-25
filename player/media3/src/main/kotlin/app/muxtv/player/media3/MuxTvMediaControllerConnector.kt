package app.muxtv.player.media3

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

@AndroidXOptIn(UnstableApi::class)
class MuxTvMediaControllerConnector(
    context: Context,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command ->
        mainHandler.post(command)
    }

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var pending: ListenableFuture<MediaController>? = null

    fun connect(): ListenableFuture<MediaController> = synchronized(lock) {
        controller?.let { existing -> return@synchronized immediateFuture(existing) }
        pending?.let { inFlight -> return@synchronized inFlight }

        val token = SessionToken(
            applicationContext,
            ComponentName(applicationContext, MuxTvPlaybackService::class.java),
        )
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        pending = future
        future.addListener(
            {
                synchronized(lock) {
                    if (pending !== future) return@synchronized
                    pending = null
                    if (!future.isCancelled) {
                        runCatching { future.get() }
                            .onSuccess { connected -> controller = connected }
                    }
                }
            },
            mainExecutor,
        )
        future
    }

    fun sendPlaybackRequest(
        controller: MediaController,
        request: PlaybackSessionRequest,
    ) = controller.sendCustomCommand(
        MuxTvPlaybackSessionContract.setPlaybackRequestCommand,
        request.toBundle(),
    )

    override fun close() {
        val controllerToRelease: MediaController?
        val pendingToCancel: ListenableFuture<MediaController>?
        synchronized(lock) {
            controllerToRelease = controller
            pendingToCancel = pending
            controller = null
            pending = null
        }
        pendingToCancel?.cancel(true)
        controllerToRelease?.let(::release)
    }

    private fun release(controller: MediaController) {
        if (Looper.myLooper() == mainHandler.looper) {
            controller.release()
        } else {
            mainHandler.post(controller::release)
        }
    }
}

private fun <T> immediateFuture(value: T): ListenableFuture<T> =
    com.google.common.util.concurrent.Futures.immediateFuture(value)

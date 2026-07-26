package app.muxtv.player.media3

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class ControllerConnectionRegistry<T : Any>(
    private val releasePending: (ListenableFuture<T>) -> Unit,
    private val releaseConnected: (T) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var state: State<T> = State.Idle()

    fun acquire(start: () -> ListenableFuture<T>): ListenableFuture<T> = synchronized(lock) {
        when (val current = state) {
            is State.Idle -> {
                val future = try {
                    start()
                } catch (error: Throwable) {
                    return@synchronized Futures.immediateFailedFuture(error)
                }
                state = State.Connecting(future)
                future
            }

            is State.Connecting -> current.future
            is State.Connected -> Futures.immediateFuture(current.controller)
            is State.Closed -> Futures.immediateFailedFuture(
                IllegalStateException(CLOSED_MESSAGE),
            )
        }
    }

    fun complete(
        future: ListenableFuture<T>,
        result: Result<T>,
    ) {
        var staleController: T? = null
        synchronized(lock) {
            when (val current = state) {
                is State.Connecting -> {
                    if (current.future === future) {
                        state = result.fold(
                            onSuccess = { controller -> State.Connected(controller) },
                            onFailure = { State.Idle() },
                        )
                    } else {
                        staleController = result.getOrNull()
                    }
                }

                is State.Closed -> {
                    if (current.releasedPending !== future) {
                        staleController = result.getOrNull()
                    }
                }

                is State.Idle,
                is State.Connected,
                -> staleController = result.getOrNull()
            }
        }
        staleController?.let(releaseConnected)
    }

    fun disconnected(controller: T) {
        synchronized(lock) {
            val current = state
            if (current is State.Connected && current.controller === controller) {
                state = State.Idle()
            }
        }
    }

    override fun close() {
        var pendingToRelease: ListenableFuture<T>? = null
        var connectedToRelease: T? = null
        synchronized(lock) {
            when (val current = state) {
                is State.Closed -> return
                is State.Connecting -> {
                    pendingToRelease = current.future
                    state = State.Closed(releasedPending = current.future)
                }

                is State.Connected -> {
                    connectedToRelease = current.controller
                    state = State.Closed()
                }

                is State.Idle -> state = State.Closed()
            }
        }
        pendingToRelease?.let(releasePending)
        connectedToRelease?.let(releaseConnected)
    }

    private sealed interface State<T : Any> {
        class Idle<T : Any> : State<T>

        data class Connecting<T : Any>(
            val future: ListenableFuture<T>,
        ) : State<T>

        data class Connected<T : Any>(
            val controller: T,
        ) : State<T>

        data class Closed<T : Any>(
            val releasedPending: ListenableFuture<T>? = null,
        ) : State<T>
    }

    private companion object {
        const val CLOSED_MESSAGE = "Controller connection registry is closed."
    }
}

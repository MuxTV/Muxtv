package app.muxtv.player.media3

import kotlinx.coroutines.TimeoutCancellationException

enum class MediaControllerOperationFailure {
    ConnectorClosed,
    ConnectionTimedOut,
    ConnectionCancelled,
    ConnectionFailed,
    CommandTimedOut,
    CommandCancelled,
    CommandFailed,
}

class MediaControllerOperationException(
    val failure: MediaControllerOperationFailure,
) : Exception("Media controller operation failed: $failure")

internal fun connectionFailureFor(error: Throwable): MediaControllerOperationFailure = when (error) {
    is ControllerConnectionRegistryClosedException -> MediaControllerOperationFailure.ConnectorClosed
    is TimeoutCancellationException -> MediaControllerOperationFailure.ConnectionTimedOut
    is ListenableFutureCancelledException -> MediaControllerOperationFailure.ConnectionCancelled
    else -> MediaControllerOperationFailure.ConnectionFailed
}

internal fun commandFailureFor(error: Throwable): MediaControllerOperationFailure = when (error) {
    is TimeoutCancellationException -> MediaControllerOperationFailure.CommandTimedOut
    is ListenableFutureCancelledException -> MediaControllerOperationFailure.CommandCancelled
    else -> MediaControllerOperationFailure.CommandFailed
}

package app.muxtv.player.media3

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> awaitPlaybackSetup(
    future: ListenableFuture<T>,
    timeoutMillis: Long,
    cancelSetup: () -> Unit,
): T = try {
    currentCoroutineContext().ensureActive()
    future.awaitCancellable(
        timeoutMillis = timeoutMillis,
        cancelFutureOnCancellation = true,
    )
} catch (cancelled: CancellationException) {
    runCatching(cancelSetup)
    throw cancelled
}

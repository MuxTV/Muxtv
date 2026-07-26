package app.muxtv.player.media3

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal class ListenableFutureCancelledException : Exception(
    "Listenable future was cancelled.",
)

internal suspend fun <T> ListenableFuture<T>.awaitCancellable(
    timeoutMillis: Long,
    cancelFutureOnCancellation: Boolean,
): T = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                if (isCancelled) {
                    continuation.resumeExceptionIfActive(ListenableFutureCancelledException())
                    return@addListener
                }

                try {
                    val value = get()
                    continuation.tryResume(value)?.let(continuation::completeResume)
                } catch (_: CancellationException) {
                    continuation.resumeExceptionIfActive(ListenableFutureCancelledException())
                } catch (error: ExecutionException) {
                    continuation.resumeExceptionIfActive(error.cause ?: error)
                } catch (error: Throwable) {
                    continuation.resumeExceptionIfActive(error)
                }
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation {
            if (cancelFutureOnCancellation) {
                cancel(true)
            }
        }
    }
}

private fun <T> CancellableContinuation<T>.resumeExceptionIfActive(error: Throwable) {
    tryResumeWithException(error)?.let(::completeResume)
}

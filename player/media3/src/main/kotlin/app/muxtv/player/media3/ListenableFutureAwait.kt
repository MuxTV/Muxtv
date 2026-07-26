package app.muxtv.player.media3

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
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
                if (!continuation.isActive) return@addListener

                if (isCancelled) {
                    continuation.resumeWith(
                        Result.failure(ListenableFutureCancelledException()),
                    )
                    return@addListener
                }

                val result = try {
                    Result.success(get())
                } catch (_: CancellationException) {
                    Result.failure(ListenableFutureCancelledException())
                } catch (error: ExecutionException) {
                    Result.failure(error.cause ?: error)
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                continuation.resumeWith(result)
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

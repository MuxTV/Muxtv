package app.muxtv.catalog.sync

import kotlinx.coroutines.CancellationException

internal suspend inline fun <T> runWorkerBoundary(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

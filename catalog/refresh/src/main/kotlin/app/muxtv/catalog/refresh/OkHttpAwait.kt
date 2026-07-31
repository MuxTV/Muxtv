package app.muxtv.catalog.refresh

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal suspend fun Call.awaitSourceResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(
                call: Call,
                exception: IOException,
            ) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }

            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        },
    )
}
package app.muxtv.network

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Interceptor
import okhttp3.Response

data class SourceRequestContext(
    val insecureHttpApproved: Boolean = false,
)

class RedirectRejectedException(
    val reason: RedirectRejectionReason,
    currentUrl: String,
) : IOException(
    "Redirect rejected: $reason from ${RedactedUri.from(currentUrl)}",
)

class SecureRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val requestContext = request.tag<SourceRequestContext>() ?: SourceRequestContext()
        var completedRedirects = 0

        while (true) {
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_STATUS_CODES) {
                return response
            }

            if (request.method != "GET" && request.method != "HEAD") {
                response.close()
                throw ProtocolException("Source redirects are supported only for GET and HEAD requests.")
            }

            when (
                val decision = RedirectPolicy.evaluate(
                    currentUrl = request.url,
                    location = response.header("Location"),
                    completedRedirects = completedRedirects,
                    insecureHttpApproved = requestContext.insecureHttpApproved,
                )
            ) {
                is RedirectDecision.Rejected -> {
                    response.close()
                    throw RedirectRejectedException(
                        reason = decision.reason,
                        currentUrl = request.url.toString(),
                    )
                }

                is RedirectDecision.Follow -> {
                    val redirectedHeaders = SensitiveHeaderPolicy.apply(
                        headers = request.headers,
                        disposition = decision.headerDisposition,
                    )
                    response.close()
                    request = request.newBuilder()
                        .url(decision.targetUrl)
                        .headers(redirectedHeaders)
                        .build()
                    completedRedirects += 1
                }
            }
        }
    }

    private companion object {
        val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

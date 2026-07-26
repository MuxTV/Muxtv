package app.muxtv.network

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

data class SourceRequestContext(
    val insecureHttpApproved: Boolean = false,
    val responseSizeLimits: ResponseSizeLimits = ResponseSizeLimits(),
)

class RedirectRejectedException(
    val reason: RedirectRejectionReason,
    currentUrl: String,
) : IOException(
    "Redirect rejected: $reason from ${RedactedUri.from(currentUrl)}",
)

class SecureRedirectInterceptor private constructor(
    private val insecureHttpApproval: (Request) -> Boolean,
) : Interceptor {
    constructor() : this(
        insecureHttpApproval = { request ->
            request.tag(SourceRequestContext::class)?.insecureHttpApproved == true
        },
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var completedRedirects = 0

        while (true) {
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_STATUS_CODES) {
                return response
            }

            if (request.method != "GET" && request.method != "HEAD") {
                response.close()
                throw ProtocolException("Redirects are supported only for GET and HEAD requests.")
            }

            when (
                val decision = RedirectPolicy.evaluate(
                    currentUrl = request.url,
                    location = response.header("Location"),
                    completedRedirects = completedRedirects,
                    insecureHttpApproved = insecureHttpApproval(request),
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

    companion object {
        /**
         * Playback cleartext redirects are allowed only when the resolved playback request carries
         * an explicit short-lived approval. HTTPS downgrade remains rejected by [RedirectPolicy].
         */
        fun forPlayback(
            insecureHttpApproved: Boolean,
        ): SecureRedirectInterceptor = SecureRedirectInterceptor(
            insecureHttpApproval = { insecureHttpApproved },
        )

        private val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

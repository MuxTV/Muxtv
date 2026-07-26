package app.muxtv.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

enum class PlaybackRequestRejectionReason {
    EmbeddedCredentials,
    HttpsDowngrade,
    InsecureTransportNotApproved,
}

class PlaybackRequestRejectedException(
    val reason: PlaybackRequestRejectionReason,
    requestUrl: String,
) : IOException(
    "Playback request rejected: $reason for ${RedactedUri.from(requestUrl)}",
)

/**
 * Enforces the playback-root security boundary for every direct manifest or media request.
 *
 * HLS subresources may be absolute URLs and therefore do not necessarily pass through redirect
 * handling. Sensitive request headers are retained only for the root origin. A secure playback
 * root may never reference an insecure HTTP subresource, and cleartext requests require an
 * explicit short-lived playback policy decision.
 */
class PlaybackRequestPolicyInterceptor(
    private val rootUrl: HttpUrl,
    private val insecureHttpApproved: Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val targetUrl = request.url

        if (targetUrl.username.isNotEmpty() || targetUrl.password.isNotEmpty()) {
            throw PlaybackRequestRejectedException(
                reason = PlaybackRequestRejectionReason.EmbeddedCredentials,
                requestUrl = targetUrl.toString(),
            )
        }
        if (rootUrl.isHttps && !targetUrl.isHttps) {
            throw PlaybackRequestRejectedException(
                reason = PlaybackRequestRejectionReason.HttpsDowngrade,
                requestUrl = targetUrl.toString(),
            )
        }
        if (!targetUrl.isHttps && !insecureHttpApproved) {
            throw PlaybackRequestRejectedException(
                reason = PlaybackRequestRejectionReason.InsecureTransportNotApproved,
                requestUrl = targetUrl.toString(),
            )
        }

        val sameOrigin = rootUrl.scheme == targetUrl.scheme &&
            rootUrl.host == targetUrl.host &&
            rootUrl.port == targetUrl.port
        val headers = SensitiveHeaderPolicy.apply(
            headers = request.headers,
            disposition = if (sameOrigin) {
                RedirectHeaderDisposition.Preserve
            } else {
                RedirectHeaderDisposition.StripSensitive
            },
        )
        return chain.proceed(
            request.newBuilder()
                .headers(headers)
                .build(),
        )
    }
}

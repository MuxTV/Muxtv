package app.muxtv.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class MuxTvHttpResources(
    val dispatcher: Dispatcher = Dispatcher(),
    val connectionPool: ConnectionPool = ConnectionPool(),
) {
    internal val baseClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

class MuxTvHttpClients(
    resources: MuxTvHttpResources = MuxTvHttpResources(),
    sourceTimingSink: MuxTvNetworkTimingSink = MuxTvNetworkTimingSink.NONE,
) {
    val source: OkHttpClient = resources.baseClient.newBuilder()
        .apply {
            if (sourceTimingSink !== MuxTvNetworkTimingSink.NONE) {
                eventListenerFactory(
                    MuxTvNetworkTimingListenerFactory(
                        clientKind = MuxTvNetworkClientKind.SOURCE,
                        sink = sourceTimingSink,
                    ),
                )
            }
        }
        .addInterceptor(SecureRedirectInterceptor())
        .addInterceptor(ResponseSizeLimitInterceptor(ResponseSizeKind.Decoded))
        .addNetworkInterceptor(ResponseSizeLimitInterceptor(ResponseSizeKind.Compressed))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    val playback: OkHttpClient = resources.baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun playbackFor(
        rootUrl: HttpUrl,
        insecureHttpApproved: Boolean,
    ): OkHttpClient = playback.newBuilder()
        .addInterceptor(SecureRedirectInterceptor.forPlayback(insecureHttpApproved))
        .addInterceptor(
            PlaybackRequestPolicyInterceptor(
                rootUrl = rootUrl,
                insecureHttpApproved = insecureHttpApproved,
            ),
        )
        .build()
}

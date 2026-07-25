package app.muxtv.player.media3

import android.content.Context
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.Call

@AndroidXOptIn(UnstableApi::class)
internal class PlaybackMediaSourceFactory(
    context: Context,
    private val callFactory: Call.Factory,
) {
    private val applicationContext = context.applicationContext

    fun create(request: PlaybackSessionRequest): MediaSource {
        val httpFactory = createHttpDataSourceFactory(request)
        val dataSourceFactory = DefaultDataSource.Factory(applicationContext, httpFactory)
        return DefaultMediaSourceFactory(applicationContext)
            .setDataSourceFactory(dataSourceFactory)
            .createMediaSource(request.toMediaItem())
    }

    internal fun createHttpDataSourceFactory(
        request: PlaybackSessionRequest,
    ): OkHttpDataSource.Factory = OkHttpDataSource.Factory(callFactory)
        .setDefaultRequestProperties(request.requestHeaders.toMap())
}

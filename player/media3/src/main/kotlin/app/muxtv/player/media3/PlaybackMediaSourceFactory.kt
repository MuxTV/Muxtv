package app.muxtv.player.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.muxtv.network.MuxTvHttpClients
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@AndroidXOptIn(UnstableApi::class)
internal class PlaybackMediaSourceFactory(
    context: Context,
    private val httpClients: MuxTvHttpClients,
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
    ): OkHttpDataSource.Factory {
        val rootUrl = request.locator.toHttpUrlOrNull()
        val callFactory = rootUrl?.let(httpClients::playbackFor) ?: httpClients.playback
        val headers = if (rootUrl == null) emptyMap() else request.requestHeaders.toMap()
        return OkHttpDataSource.Factory(callFactory)
            .setDefaultRequestProperties(headers)
    }
}

private fun PlaybackSessionRequest.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder().apply {
        displayName?.let(::setTitle)
        artworkUri?.let { setArtworkUri(Uri.parse(it)) }
    }.build()

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.parse(locator))
        .setMediaMetadata(metadata)
        .build()
}

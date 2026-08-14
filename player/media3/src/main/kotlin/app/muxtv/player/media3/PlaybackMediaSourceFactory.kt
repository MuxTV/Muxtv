package app.muxtv.player.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
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
        val mediaItem = request.toMediaItem()
        return when (PlaybackTransportClassifier.classify(request.locator).sourcePolicy.kind) {
            PlaybackMediaSourceKind.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            PlaybackMediaSourceKind.DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            PlaybackMediaSourceKind.PROGRESSIVE -> {
                val decision = PlaybackTransportClassifier.classify(request.locator)
                val extractorMode = decision.sourcePolicy.tsExtractorMode
                if (extractorMode == null) {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                } else {
                    val extractorsFactory = ExtractorsFactory {
                        arrayOf(TsExtractor(extractorMode))
                    }
                    ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItem)
                }
            }
            PlaybackMediaSourceKind.AUTO -> DefaultMediaSourceFactory(applicationContext)
                .setDataSourceFactory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    internal fun createHttpDataSourceFactory(
        request: PlaybackSessionRequest,
    ): OkHttpDataSource.Factory {
        val rootUrl = request.locator.toHttpUrlOrNull()
        val callFactory = rootUrl?.let { url ->
            httpClients.playbackFor(
                rootUrl = url,
                insecureHttpApproved = request.insecureHttpApproved,
            )
        } ?: httpClients.playback
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
        .apply {
            mimeType?.let(::setMimeType)
        }
        .build()
}

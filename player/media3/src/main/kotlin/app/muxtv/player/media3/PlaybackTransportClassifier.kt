package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.TsExtractor
import java.net.URI
import java.util.Locale

internal enum class PlaybackTransport {
    HLS,
    MPEG_TS_LIVE,
    DASH,
    PROGRESSIVE,
    AUTO,
}

internal enum class PlaybackTransportReason {
    URI_PATH_SUFFIX,
    NO_RELIABLE_HINT,
}

internal enum class PlaybackMediaSourceKind {
    HLS,
    PROGRESSIVE,
    DASH,
    AUTO,
}

internal data class PlaybackMediaSourcePolicy(
    val kind: PlaybackMediaSourceKind,
    val tsExtractorMode: Int? = null,
)

internal data class PlaybackTransportDecision(
    val transport: PlaybackTransport,
    val reason: PlaybackTransportReason,
    val sourcePolicy: PlaybackMediaSourcePolicy,
) {
    override fun toString(): String =
        "PlaybackTransportDecision(transport=$transport, reason=$reason, sourcePolicy=$sourcePolicy)"
}

@AndroidXOptIn(UnstableApi::class)
internal object PlaybackTransportClassifier {
    private val progressiveSuffixes = setOf(
        ".mp4",
        ".m4v",
        ".mov",
        ".mkv",
        ".webm",
        ".mp3",
        ".m4a",
        ".aac",
        ".flac",
        ".ogg",
        ".oga",
        ".opus",
        ".wav",
    )

    fun classify(locator: String): PlaybackTransportDecision {
        val path = runCatching { URI(locator).path }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        return when {
            path.endsWith(".m3u8") -> decision(PlaybackTransport.HLS)
            path.endsWith(".ts") -> decision(PlaybackTransport.MPEG_TS_LIVE)
            path.endsWith(".mpd") -> decision(PlaybackTransport.DASH)
            progressiveSuffixes.any(path::endsWith) -> decision(PlaybackTransport.PROGRESSIVE)
            else -> PlaybackTransportDecision(
                transport = PlaybackTransport.AUTO,
                reason = PlaybackTransportReason.NO_RELIABLE_HINT,
                sourcePolicy = PlaybackMediaSourcePolicy(PlaybackMediaSourceKind.AUTO),
            )
        }
    }

    private fun decision(transport: PlaybackTransport): PlaybackTransportDecision =
        PlaybackTransportDecision(
            transport = transport,
            reason = PlaybackTransportReason.URI_PATH_SUFFIX,
            sourcePolicy = when (transport) {
                PlaybackTransport.HLS -> PlaybackMediaSourcePolicy(PlaybackMediaSourceKind.HLS)
                PlaybackTransport.MPEG_TS_LIVE -> PlaybackMediaSourcePolicy(
                    kind = PlaybackMediaSourceKind.PROGRESSIVE,
                    tsExtractorMode = TsExtractor.MODE_SINGLE_PMT,
                )
                PlaybackTransport.DASH -> PlaybackMediaSourcePolicy(PlaybackMediaSourceKind.DASH)
                PlaybackTransport.PROGRESSIVE -> PlaybackMediaSourcePolicy(
                    PlaybackMediaSourceKind.PROGRESSIVE,
                )
                PlaybackTransport.AUTO -> PlaybackMediaSourcePolicy(PlaybackMediaSourceKind.AUTO)
            },
        )
}

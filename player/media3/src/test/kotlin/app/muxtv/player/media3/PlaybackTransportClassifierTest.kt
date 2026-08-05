package app.muxtv.player.media3

import androidx.media3.extractor.ts.TsExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackTransportClassifierTest {
    @Test
    fun hlsSuffixIsClassifiedWithoutReadingQueryValues() {
        val decision = PlaybackTransportClassifier.classify(
            "https://example.invalid/live/CHANNEL.M3U8?token=sensitive",
        )

        assertThat(decision.transport).isEqualTo(PlaybackTransport.HLS)
        assertThat(decision.reason).isEqualTo(PlaybackTransportReason.URI_PATH_SUFFIX)
        assertThat(decision.toString()).doesNotContain("example.invalid")
        assertThat(decision.toString()).doesNotContain("sensitive")
    }

    @Test
    fun rawTransportStreamSuffixIsExplicitLiveMpegTs() {
        val decision = PlaybackTransportClassifier.classify(
            "https://example.invalid/live/channel.ts",
        )

        assertThat(decision.transport).isEqualTo(PlaybackTransport.MPEG_TS_LIVE)
        assertThat(decision.sourcePolicy.kind).isEqualTo(PlaybackMediaSourceKind.PROGRESSIVE)
        assertThat(decision.sourcePolicy.tsExtractorMode).isEqualTo(TsExtractor.MODE_SINGLE_PMT)
        assertThat(decision.sourcePolicy.tsExtractorMode).isNotEqualTo(TsExtractor.MODE_HLS)
    }

    @Test
    fun dashSuffixIsClassifiedExplicitly() {
        val decision = PlaybackTransportClassifier.classify(
            "https://example.invalid/live/channel.mpd",
        )

        assertThat(decision.transport).isEqualTo(PlaybackTransport.DASH)
        assertThat(decision.sourcePolicy.kind).isEqualTo(PlaybackMediaSourceKind.DASH)
    }

    @Test
    fun ordinaryProgressiveSuffixUsesProgressiveSourcePolicy() {
        val decision = PlaybackTransportClassifier.classify(
            "https://example.invalid/archive/clip.mp4",
        )

        assertThat(decision.transport).isEqualTo(PlaybackTransport.PROGRESSIVE)
        assertThat(decision.sourcePolicy.kind).isEqualTo(PlaybackMediaSourceKind.PROGRESSIVE)
        assertThat(decision.sourcePolicy.tsExtractorMode).isNull()
    }

    @Test
    fun suffixlessUriRemainsAutoRatherThanGuessingFromQuery() {
        val decision = PlaybackTransportClassifier.classify(
            "https://example.invalid/live?id=channel.ts&format=m3u8",
        )

        assertThat(decision.transport).isEqualTo(PlaybackTransport.AUTO)
        assertThat(decision.reason).isEqualTo(PlaybackTransportReason.NO_RELIABLE_HINT)
        assertThat(decision.sourcePolicy.kind).isEqualTo(PlaybackMediaSourceKind.AUTO)
    }

    @Test
    fun misleadingDirectoryNameDoesNotClassifyAsTransportSuffix() {
        val decision = PlaybackTransportClassifier.classify(
            "https://example.invalid/m3u8/channel",
        )

        assertThat(decision.transport).isEqualTo(PlaybackTransport.AUTO)
    }
}

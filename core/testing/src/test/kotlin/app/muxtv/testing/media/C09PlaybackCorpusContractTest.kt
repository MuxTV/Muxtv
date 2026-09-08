package app.muxtv.testing.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class C09PlaybackCorpusContractTest {
    @Test
    fun `corpus is immutable bounded and covers registered decoder scenarios`() {
        assertThat(C09PlaybackCorpus.manifestSha256)
            .isEqualTo("c0a7c7a9b8f09f5c33b85519d82e2996e1d2cbc62ad8339767f44fa7dc71fc3c")
        assertThat(C09PlaybackCorpus.contentSha256)
            .isEqualTo("10c41a6ef0de1834fcf6658d5c8edddf1abb9e52d933553fb3c108220ccb36b5")

        assertThat(C09PlaybackCorpus.fixtures).hasSize(6)
        assertThat(C09PlaybackCorpus.fixtures.map { it.id }).containsExactly(
            "raw-avc-360p30",
            "raw-avc-720p50",
            "raw-hevc-360p30",
            "raw-hevc-720p50",
            "hls-avc-360p30",
            "hls-hevc-360p30",
        )
        assertThat(C09PlaybackCorpus.fixtures.map { it.transport }.toSet())
            .containsExactly(C09PlaybackTransport.MPEG_TS, C09PlaybackTransport.HLS)
        assertThat(C09PlaybackCorpus.fixtures.map { it.codec }.toSet())
            .containsExactly(C09PlaybackCodec.AVC, C09PlaybackCodec.HEVC)
        assertThat(C09PlaybackCorpus.fixtures.maxOf { it.widthPixels }).isEqualTo(1_280)
        assertThat(C09PlaybackCorpus.fixtures.maxOf { it.framesPerSecond }).isEqualTo(50)

        assertThat(C09PlaybackCorpus.verifyIntegrity()).isEmpty()
    }
}

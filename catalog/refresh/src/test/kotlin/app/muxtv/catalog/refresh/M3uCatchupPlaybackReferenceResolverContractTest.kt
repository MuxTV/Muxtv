package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackCatchupMetadata
import app.muxtv.catalog.PlaybackCatchupUnavailableReason
import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class M3uCatchupPlaybackReferenceResolverContractTest {
    @Test
    fun catchupPositionMaterializesDirectArchiveTransportWithoutFallbackOrSecretDiagnostics() =
        runTest {
            val fallbackRequests = mutableListOf<PlaybackReferenceRequest>()
            val resolver = M3uCatchupPlaybackReferenceResolver(
                fallback = PlaybackReferenceResolver { request ->
                    fallbackRequests += request
                    PlaybackReferenceResolution.Unhandled
                },
                nowEpochMillis = { NOW },
            )
            val intent = PlaybackIntent.CatchupPosition(
                channelId = "channel-catchup",
                positionEpochMillis = POSITION,
            )

            val result = resolver.resolve(
                PlaybackReferenceRequest(
                    credentialRef = "credential-m3u",
                    playbackReference = LIVE_LOCATOR,
                    intent = intent,
                    catchupMetadata = PlaybackCatchupMetadata(
                        mode = "append",
                        sourceTemplate = CATCHUP_SOURCE,
                        retentionDays = 7,
                        correction = "+2.0",
                    ),
                ),
            ) as PlaybackReferenceResolution.MaterializedDirect

            val expectedUtcSeconds = (POSITION - (2 * HOUR_MILLIS)) / 1_000L
            assertThat(result.locator)
                .isEqualTo("$LIVE_LOCATOR?utc=$expectedUtcSeconds&token=$CATCHUP_SECRET")
            assertThat(result.timeline.initialPositionEpochMillis).isEqualTo(POSITION)
            assertThat(result.timeline.correctionMillis).isEqualTo(2 * HOUR_MILLIS)
            assertThat(result.toString()).doesNotContain(LIVE_SECRET)
            assertThat(result.toString()).doesNotContain(CATCHUP_SECRET)
            assertThat(fallbackRequests).isEmpty()
        }

    @Test
    fun legacyLiveReferenceDelegatesUnchangedToExistingProviderResolver() = runTest {
        val fallbackRequests = mutableListOf<PlaybackReferenceRequest>()
        val resolver = M3uCatchupPlaybackReferenceResolver(
            fallback = PlaybackReferenceResolver { request ->
                fallbackRequests += request
                PlaybackReferenceResolution.InvalidReference
            },
            nowEpochMillis = { NOW },
        )
        val request = PlaybackReferenceRequest(
            credentialRef = "credential-live",
            playbackReference = LIVE_LOCATOR,
        )

        val result = resolver.resolve(request)

        assertThat(result).isEqualTo(PlaybackReferenceResolution.InvalidReference)
        assertThat(fallbackRequests).containsExactly(request)
    }

    @Test
    fun unsupportedCatchupMetadataReturnsTypedUnavailableInsteadOfLiveFallback() = runTest {
        val fallbackRequests = mutableListOf<PlaybackReferenceRequest>()
        val resolver = M3uCatchupPlaybackReferenceResolver(
            fallback = PlaybackReferenceResolver { request ->
                fallbackRequests += request
                PlaybackReferenceResolution.Unhandled
            },
            nowEpochMillis = { NOW },
        )

        val result = resolver.resolve(
            PlaybackReferenceRequest(
                credentialRef = "credential-m3u",
                playbackReference = LIVE_LOCATOR,
                intent = PlaybackIntent.CatchupPosition(
                    channelId = "channel-catchup",
                    positionEpochMillis = POSITION,
                ),
                catchupMetadata = PlaybackCatchupMetadata(
                    mode = "unsupported-mode",
                    sourceTemplate = CATCHUP_SOURCE,
                    retentionDays = 7,
                    correction = "0",
                ),
            ),
        )

        assertThat(result).isEqualTo(
            PlaybackReferenceResolution.CatchupUnavailable(
                PlaybackCatchupUnavailableReason.UNSUPPORTED,
            ),
        )
        assertThat(fallbackRequests).isEmpty()
        assertThat(result.toString()).doesNotContain(CATCHUP_SECRET)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR_MILLIS = 60 * 60 * 1_000L
        const val POSITION = NOW - (3 * HOUR_MILLIS)
        const val LIVE_SECRET = "TEST_LIVE_REFERENCE_SECRET"
        const val CATCHUP_SECRET = "TEST_CATCHUP_REFERENCE_SECRET"
        const val LIVE_LOCATOR = "https://streams.invalid/live/catchup.m3u8?live=$LIVE_SECRET"
        const val CATCHUP_SOURCE = "?utc={utc}&token=$CATCHUP_SECRET"
    }
}

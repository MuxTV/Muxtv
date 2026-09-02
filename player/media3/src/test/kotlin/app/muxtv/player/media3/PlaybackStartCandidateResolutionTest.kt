package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.PlaybackStartRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackStartCandidateResolutionTest {
    @Test
    fun archiveStartResolvesExactCandidateWithOriginalIntent() = runTest {
        val intent = PlaybackIntent.CatchupProgram(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            startEpochMillis = PROGRAMME_START,
            endEpochMillis = PROGRAMME_END,
        )
        val request = PlaybackStartRequest(
            profileId = PROFILE_ID,
            intent = intent,
            preferredVariantId = VARIANT_ID,
        )
        val candidate = PlaybackCandidateIdentity(
            channelId = CHANNEL_ID,
            variantId = VARIANT_ID,
        )
        val resolver = RecordingResolver()

        val resolution = resolvePlaybackStartCandidate(
            resolver = resolver,
            request = request,
            candidate = candidate,
        )

        assertThat(resolution).isEqualTo(ARCHIVE_UNAVAILABLE)
        assertThat(resolver.intentCalls).containsExactly(
            IntentCall(PROFILE_ID, intent, candidate),
        )
        assertThat(resolver.liveCandidateCalls).isEmpty()
    }

    private class RecordingResolver : PlaybackCandidateResolver {
        val intentCalls = mutableListOf<IntentCall>()
        val liveCandidateCalls = mutableListOf<PlaybackCandidateIdentity>()

        override suspend fun getCandidates(
            profileId: String,
            channelId: String,
            preferredVariantId: String?,
            limit: Int,
        ): List<PlaybackCandidateIdentity> = emptyList()

        override suspend fun resolveCandidate(
            profileId: String,
            candidate: PlaybackCandidateIdentity,
        ): PlaybackVariantResolution {
            liveCandidateCalls += candidate
            return ARCHIVE_UNAVAILABLE
        }

        override suspend fun resolveIntentCandidate(
            profileId: String,
            intent: PlaybackIntent,
            candidate: PlaybackCandidateIdentity,
        ): PlaybackVariantResolution {
            intentCalls += IntentCall(profileId, intent, candidate)
            return ARCHIVE_UNAVAILABLE
        }
    }

    private data class IntentCall(
        val profileId: String,
        val intent: PlaybackIntent,
        val candidate: PlaybackCandidateIdentity,
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-news"
        const val VARIANT_ID = "variant-primary"
        const val PROGRAMME_ID = "programme-42"
        const val PROGRAMME_START = 1_800_000_000_000L
        const val PROGRAMME_END = PROGRAMME_START + 3_600_000L
        val ARCHIVE_UNAVAILABLE = PlaybackVariantResolution.AccessUnavailable(
            PlaybackAccessUnavailableReason.ArchiveUnsupported,
        )
    }
}

package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.PlaybackStartRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackIntentRecoveryResolutionTest {
    @Test
    fun recoveryCandidatesReceiveTheSameCatchupIntentWithoutUsingLegacyResolver() = runTest {
        val intent = PlaybackIntent.CatchupProgram(
            channelId = CHANNEL_ID,
            programmeId = PROGRAMME_ID,
            startEpochMillis = PROGRAMME_START,
            endEpochMillis = PROGRAMME_END,
        )
        val request = PlaybackStartRequest(
            profileId = PROFILE_ID,
            intent = intent,
        )
        val first = PlaybackCandidateIdentity(CHANNEL_ID, FIRST_VARIANT_ID)
        val second = PlaybackCandidateIdentity(CHANNEL_ID, SECOND_VARIANT_ID)
        val calls = mutableListOf<IntentCandidateCall>()
        val resolver = object : PlaybackCandidateResolver {
            override suspend fun getCandidates(
                profileId: String,
                channelId: String,
                preferredVariantId: String?,
                limit: Int,
            ): List<PlaybackCandidateIdentity> = listOf(first, second)

            override suspend fun resolveCandidate(
                profileId: String,
                candidate: PlaybackCandidateIdentity,
            ): PlaybackVariantResolution = error("legacy Live resolver must not handle archive recovery")

            override suspend fun resolveIntentCandidate(
                profileId: String,
                intent: PlaybackIntent,
                candidate: PlaybackCandidateIdentity,
            ): PlaybackVariantResolution {
                calls += IntentCandidateCall(profileId, intent, candidate)
                return if (candidate == first) {
                    PlaybackVariantResolution.AccessUnavailable(
                        PlaybackAccessUnavailableReason.ArchiveOutsideRetention,
                    )
                } else {
                    PlaybackVariantResolution.Ready(
                        ResolvedPlaybackRequest(
                            channelId = candidate.channelId,
                            variantId = candidate.variantId,
                            locator = "https://archive.invalid/ready.m3u8",
                            requestHeaders = emptyMap(),
                            insecureHttpApproved = false,
                        ),
                    )
                }
            }
        }

        val firstResolution = resolvePlaybackCandidateForRecovery(
            resolver = resolver,
            request = request,
            candidate = first,
        )
        val secondResolution = resolvePlaybackCandidateForRecovery(
            resolver = resolver,
            request = request,
            candidate = second,
        )

        assertThat(firstResolution).isEqualTo(
            PlaybackVariantResolution.AccessUnavailable(
                PlaybackAccessUnavailableReason.ArchiveOutsideRetention,
            ),
        )
        assertThat(secondResolution).isInstanceOf(PlaybackVariantResolution.Ready::class.java)
        assertThat(calls.map { it.candidate }).containsExactly(first, second).inOrder()
        assertThat(calls.map { it.intent }).containsExactly(intent, intent).inOrder()
        assertThat(calls.map { it.profileId }).containsExactly(PROFILE_ID, PROFILE_ID).inOrder()
    }

    private data class IntentCandidateCall(
        val profileId: String,
        val intent: PlaybackIntent,
        val candidate: PlaybackCandidateIdentity,
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-news"
        const val FIRST_VARIANT_ID = "variant-a"
        const val SECOND_VARIANT_ID = "variant-b"
        const val PROGRAMME_ID = "programme-revision-7-sequence-42"
        const val PROGRAMME_START = 1_799_985_600_000L
        const val PROGRAMME_END = PROGRAMME_START + 3_600_000L
    }
}

package app.muxtv.catalog

import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PlaybackCandidateResolverIntentTest {
    @Test
    fun liveIntentDefaultsToExistingExactCandidateResolution(): Unit = runBlocking {
        val candidate = PlaybackCandidateIdentity(
            channelId = CHANNEL_ID,
            variantId = VARIANT_ID,
        )
        val expected = PlaybackVariantResolution.AccessUnavailable(
            PlaybackAccessUnavailableReason.CredentialUnavailable,
        )
        val resolvedCandidates = mutableListOf<PlaybackCandidateIdentity>()
        val resolver = recordingResolver(resolvedCandidates, expected)

        val result = resolver.resolveIntentCandidate(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.Live(CHANNEL_ID),
            candidate = candidate,
        )

        assertThat(result).isEqualTo(expected)
        assertThat(resolvedCandidates).containsExactly(candidate)
        Unit
    }

    @Test
    fun archiveIntentDefaultsToUnsupportedWithoutFallingBackToLiveCandidateResolution(): Unit =
        runBlocking {
            val candidate = PlaybackCandidateIdentity(
                channelId = CHANNEL_ID,
                variantId = VARIANT_ID,
            )
            val resolvedCandidates = mutableListOf<PlaybackCandidateIdentity>()
            val resolver = recordingResolver(
                resolvedCandidates = resolvedCandidates,
                resolution = PlaybackVariantResolution.AccessUnavailable(
                    PlaybackAccessUnavailableReason.CredentialUnavailable,
                ),
            )

            val result = resolver.resolveIntentCandidate(
                profileId = PROFILE_ID,
                intent = PlaybackIntent.CatchupPosition(
                    channelId = CHANNEL_ID,
                    positionEpochMillis = 1_800_000_000_000L,
                ),
                candidate = candidate,
            )

            assertThat(result).isEqualTo(
                PlaybackVariantResolution.AccessUnavailable(
                    PlaybackAccessUnavailableReason.ArchiveUnsupported,
                ),
            )
            assertThat(resolvedCandidates).isEmpty()
            Unit
        }

    private fun recordingResolver(
        resolvedCandidates: MutableList<PlaybackCandidateIdentity>,
        resolution: PlaybackVariantResolution,
    ): PlaybackCandidateResolver = object : PlaybackCandidateResolver {
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
            resolvedCandidates += candidate
            return resolution
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-news"
        const val VARIANT_ID = "variant-primary"
    }
}

package app.muxtv.player.media3

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSetupCommandCodecTest {
    @Test
    fun setupArgsRoundTripIdentityOnlyRequest() {
        val setupId = setupId("10000000-0000-0000-0000-000000000001")
        val request = request()

        val encoded = MuxTvPlaybackSessionContract.setupArgs(setupId, request)
        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(encoded)

        assertThat(encoded.keySet()).containsExactly("setup_id", "request")
        assertThat(requireNotNull(encoded.getBundle("request")).keySet())
            .containsExactly("profile_id", "channel_id", "preferred_variant_id")
        assertThat(decoded).isEqualTo(PlaybackSetupCommand(setupId, request))
    }

    @Test
    fun setupArgsWithoutPreferredVariantEncodeOnlyRequiredIdentities() {
        val encoded = MuxTvPlaybackSessionContract.setupArgs(
            setupId("10000000-0000-0000-0000-000000000002"),
            PlaybackStartRequest(PROFILE_ID, CHANNEL_ID, preferredVariantId = null),
        )

        assertThat(requireNotNull(encoded.getBundle("request")).keySet())
            .containsExactly("profile_id", "channel_id")
        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(encoded)!!.request.preferredVariantId)
            .isNull()
    }

    @Test
    fun cancelArgsRoundTripId() {
        val setupId = setupId("10000000-0000-0000-0000-000000000003")

        val decoded = MuxTvPlaybackSessionContract.parseCancelArgs(
            MuxTvPlaybackSessionContract.cancelArgs(setupId),
        )

        assertThat(decoded).isEqualTo(setupId)
    }

    @Test
    fun legacyLocatorHeadersAndArtworkCannotCrossTheCodecBoundary() {
        val locator = "https://provider.invalid/live.m3u8?token=codec-secret"
        val headerValue = "Bearer codec-secret"
        val artwork = "https://images.invalid/news.png"
        val encoded = MuxTvPlaybackSessionContract.setupArgs(
            setupId("10000000-0000-0000-0000-000000000004"),
            request(),
        )
        val requestBundle = requireNotNull(encoded.getBundle("request"))

        assertThat(encoded.keySet()).doesNotContain("locator")
        assertThat(encoded.keySet()).doesNotContain("headers")
        assertThat(encoded.keySet()).doesNotContain("artwork_uri")
        assertThat(requestBundle.keySet()).doesNotContain("locator")
        assertThat(requestBundle.keySet()).doesNotContain("headers")
        assertThat(requestBundle.keySet()).doesNotContain("artwork_uri")

        val legacyBundle = Bundle().apply {
            putString("setup_id", "10000000-0000-0000-0000-000000000004")
            putBundle(
                "request",
                Bundle().apply {
                    putString("profile_id", PROFILE_ID)
                    putString("channel_id", CHANNEL_ID)
                    putString("preferred_variant_id", PREFERRED_VARIANT_ID)
                    putString("locator", locator)
                    putString("headers", headerValue)
                    putString("artwork_uri", artwork)
                },
            )
        }

        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(legacyBundle)).isNull()
    }

    @Test
    fun malformedBundlesFailClosed() {
        val setupId = "10000000-0000-0000-0000-000000000005"
        val validRequestBundle = Bundle().apply {
            putString("profile_id", PROFILE_ID)
            putString("channel_id", CHANNEL_ID)
        }

        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(Bundle())).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseSetupArgs(
                Bundle().apply { putString("setup_id", setupId) },
            ),
        ).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseSetupArgs(
                Bundle().apply {
                    putString("setup_id", setupId)
                    putBundle("request", Bundle().apply { putString("profile_id", PROFILE_ID) })
                },
            ),
        ).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseSetupArgs(
                Bundle().apply {
                    putString("setup_id", setupId)
                    putBundle("request", validRequestBundle)
                    putString("unexpected", "value")
                },
            ),
        ).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseSetupArgs(
                Bundle().apply {
                    putString("setup_id", setupId)
                    putBundle(
                        "request",
                        Bundle().apply {
                            putString("profile_id", PROFILE_ID)
                            putString("channel_id", CHANNEL_ID)
                            putString("preferred_variant_id", " ")
                        },
                    )
                },
            ),
        ).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseCancelArgs(
                Bundle().apply {
                    putString("setup_id", setupId)
                    putString("unexpected", "value")
                },
            ),
        ).isNull()
    }

    @Test
    fun commandDiagnosticsRedactSetupAndRequestIdentities() {
        val rawSetupId = "10000000-0000-0000-0000-000000000006"
        val command = PlaybackSetupCommand(
            id = setupId(rawSetupId),
            request = request(),
        )

        assertThat(command.toString()).contains("<redacted>")
        assertThat(command.toString()).doesNotContain(rawSetupId)
        assertThat(command.toString()).doesNotContain(PROFILE_ID)
        assertThat(command.toString()).doesNotContain(CHANNEL_ID)
        assertThat(command.toString()).doesNotContain(PREFERRED_VARIANT_ID)
    }

    @Test
    fun cancelledResultUsesBinderSafeInvalidStateCode() {
        val result = MuxTvPlaybackSessionContract.cancelled()

        assertThat(result.resultCode)
            .isEqualTo(androidx.media3.session.SessionError.ERROR_INVALID_STATE)
    }

    @Test
    fun typedResultsRoundTripWithoutSecretBearingFields() {
        val results = listOf(
            PlaybackStartResult.Started,
            PlaybackStartResult.InsecureHttpApprovalRequired(
                displayOrigin = "http://cdn.example:8080",
                variantId = "variant-http",
            ),
            PlaybackStartResult.Rejected(
                reason = PlaybackStartFailure.RecoveryExhausted,
                observationAvailable = true,
            ),
        )

        results.forEach { expected ->
            val encoded = MuxTvPlaybackSessionContract.result(expected)
            assertThat(MuxTvPlaybackSessionContract.parseResult(encoded)).isEqualTo(expected)
            assertThat(encoded.extras.toString()).doesNotContain("token=")
            assertThat(encoded.extras.keySet()).doesNotContain("locator")
            assertThat(encoded.extras.keySet()).doesNotContain("headers")
            assertThat(encoded.extras.keySet()).doesNotContain("credentials")
        }
    }

    @Test
    fun unknownResultPayloadFailsClosed() {
        val malformed = androidx.media3.session.SessionResult(
            androidx.media3.session.SessionResult.RESULT_SUCCESS,
            Bundle().apply {
                putString("result_kind", "started")
                putString("unexpected", "secret")
            },
        )

        assertThat(MuxTvPlaybackSessionContract.parseResult(malformed)).isNull()
    }

    private fun setupId(raw: String): PlaybackSetupId =
        requireNotNull(PlaybackSetupId.parse(raw))

    private fun request() = PlaybackStartRequest(
        profileId = PROFILE_ID,
        channelId = CHANNEL_ID,
        preferredVariantId = PREFERRED_VARIANT_ID,
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-news"
        const val PREFERRED_VARIANT_ID = "variant-primary"
    }
}

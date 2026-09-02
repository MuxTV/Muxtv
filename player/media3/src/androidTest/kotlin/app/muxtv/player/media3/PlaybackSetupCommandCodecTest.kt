package app.muxtv.player.media3

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
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
    fun catchupProgrammeRoundTripsOnlyProviderNeutralSemanticFields() {
        val setupId = setupId("10000000-0000-0000-0000-000000000007")
        val request = PlaybackStartRequest(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.CatchupProgram(
                channelId = CHANNEL_ID,
                programmeId = PROGRAMME_ID,
                startEpochMillis = PROGRAMME_START,
                endEpochMillis = PROGRAMME_END,
            ),
            preferredVariantId = PREFERRED_VARIANT_ID,
        )

        val encoded = MuxTvPlaybackSessionContract.setupArgs(setupId, request)
        val requestBundle = requireNotNull(encoded.getBundle("request"))
        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(encoded)

        assertThat(requestBundle.keySet()).containsExactly(
            "profile_id",
            "channel_id",
            "preferred_variant_id",
            "intent_kind",
            "programme_id",
            "programme_start_epoch_millis",
            "programme_end_epoch_millis",
        )
        assertThat(requestBundle.getString("intent_kind")).isEqualTo("catchup_program")
        assertThat(requestBundle.getString("programme_id")).isEqualTo(PROGRAMME_ID)
        assertThat(requestBundle.getLong("programme_start_epoch_millis")).isEqualTo(PROGRAMME_START)
        assertThat(requestBundle.getLong("programme_end_epoch_millis")).isEqualTo(PROGRAMME_END)
        assertThat(decoded).isEqualTo(PlaybackSetupCommand(setupId, request))
        assertThat(requestBundle.keySet()).doesNotContain("locator")
        assertThat(requestBundle.keySet()).doesNotContain("headers")
        assertThat(requestBundle.keySet()).doesNotContain("credentials")
    }

    @Test
    fun catchupPositionRoundTripsOnlyProviderNeutralSemanticFields() {
        val setupId = setupId("10000000-0000-0000-0000-000000000008")
        val request = PlaybackStartRequest(
            profileId = PROFILE_ID,
            intent = PlaybackIntent.CatchupPosition(
                channelId = CHANNEL_ID,
                positionEpochMillis = PROGRAMME_START,
            ),
        )

        val encoded = MuxTvPlaybackSessionContract.setupArgs(setupId, request)
        val requestBundle = requireNotNull(encoded.getBundle("request"))
        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(encoded)

        assertThat(requestBundle.keySet()).containsExactly(
            "profile_id",
            "channel_id",
            "intent_kind",
            "position_epoch_millis",
        )
        assertThat(requestBundle.getString("intent_kind")).isEqualTo("catchup_position")
        assertThat(requestBundle.getLong("position_epoch_millis")).isEqualTo(PROGRAMME_START)
        assertThat(decoded).isEqualTo(PlaybackSetupCommand(setupId, request))
    }

    @Test
    fun malformedCatchupSemanticPayloadsFailClosed() {
        val setupId = "10000000-0000-0000-0000-000000000009"

        val missingProgrammeEnd = setupBundle(
            setupId = setupId,
            request = Bundle().apply {
                putString("profile_id", PROFILE_ID)
                putString("channel_id", CHANNEL_ID)
                putString("intent_kind", "catchup_program")
                putString("programme_id", PROGRAMME_ID)
                putLong("programme_start_epoch_millis", PROGRAMME_START)
            },
        )
        val unknownKind = setupBundle(
            setupId = setupId,
            request = Bundle().apply {
                putString("profile_id", PROFILE_ID)
                putString("channel_id", CHANNEL_ID)
                putString("intent_kind", "provider_specific_secret_mode")
            },
        )
        val secretBearingExtra = setupBundle(
            setupId = setupId,
            request = Bundle().apply {
                putString("profile_id", PROFILE_ID)
                putString("channel_id", CHANNEL_ID)
                putString("intent_kind", "catchup_position")
                putLong("position_epoch_millis", PROGRAMME_START)
                putString("locator", "https://provider.invalid/archive?token=codec-secret")
            },
        )

        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(missingProgrammeEnd)).isNull()
        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(unknownKind)).isNull()
        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(secretBearingExtra)).isNull()
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
            PlaybackStartResult.LocalNetworkPermissionRequired(
                variantId = "variant-local",
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
    fun localNetworkPermissionPayloadWithSecretBearingExtraFailsClosed() {
        val malformed = androidx.media3.session.SessionResult(
            androidx.media3.session.SessionResult.RESULT_SUCCESS,
            Bundle().apply {
                putString("result_kind", "local_network_permission_required")
                putString("variant_id", "variant-local")
                putString("locator", "http://192.168.1.20/live.m3u8?token=codec-secret")
            },
        )

        assertThat(MuxTvPlaybackSessionContract.parseResult(malformed)).isNull()
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

    private fun setupBundle(setupId: String, request: Bundle): Bundle = Bundle().apply {
        putString("setup_id", setupId)
        putBundle("request", request)
    }

    private fun request() = PlaybackStartRequest(
        profileId = PROFILE_ID,
        channelId = CHANNEL_ID,
        preferredVariantId = PREFERRED_VARIANT_ID,
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-news"
        const val PREFERRED_VARIANT_ID = "variant-primary"
        const val PROGRAMME_ID = "programme-epg-42"
        const val PROGRAMME_START = 1_800_000_000_000L
        const val PROGRAMME_END = PROGRAMME_START + 3_600_000L
    }
}
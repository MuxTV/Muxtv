package app.muxtv.player.media3

import androidx.media3.common.PlaybackException
import app.muxtv.player.PlaybackFailureCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3RecoveryDispositionPolicyTest {
    @Test
    fun `A preserves current generic next behavior for every typed failure`() {
        val failures = listOf(
            failure(PlaybackFailureCategory.TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT),
            failure(PlaybackFailureCategory.CODEC_DECODER, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK),
            failure(PlaybackFailureCategory.UNKNOWN, PlaybackException.ERROR_CODE_UNSPECIFIED),
        )

        failures.forEach { failure ->
            assertThat(
                Media3RecoveryDispositionPolicy.disposition(
                    variant = Media3RecoveryPolicyVariant.A_CURRENT_GENERIC_NEXT,
                    failure = failure,
                ),
            ).isEqualTo(PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE)
        }
    }

    @Test
    fun `B stops only exact failed runtime check`() {
        assertThat(
            Media3RecoveryDispositionPolicy.disposition(
                variant = Media3RecoveryPolicyVariant.B_NARROW_TYPED_STOP,
                failure = failure(
                    PlaybackFailureCategory.PLAYER_RENDER,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                ),
            ),
        ).isEqualTo(PlaybackRecoveryDisposition.STOP_RECOVERY)

        listOf(
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED),
            failure(PlaybackFailureCategory.CODEC_DECODER, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
            failure(PlaybackFailureCategory.TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT),
            failure(PlaybackFailureCategory.CREDENTIAL_ACCESS, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED),
        ).forEach { failure ->
            assertThat(
                Media3RecoveryDispositionPolicy.disposition(
                    variant = Media3RecoveryPolicyVariant.B_NARROW_TYPED_STOP,
                    failure = failure,
                ),
            ).isEqualTo(PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE)
        }
    }

    @Test
    fun `C stops category level player render failures but not candidate source families`() {
        listOf(
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK),
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW),
            failure(PlaybackFailureCategory.PLAYER_RENDER, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED),
        ).forEach { failure ->
            assertThat(
                Media3RecoveryDispositionPolicy.disposition(
                    variant = Media3RecoveryPolicyVariant.C_BROAD_RENDER_STOP,
                    failure = failure,
                ),
            ).isEqualTo(PlaybackRecoveryDisposition.STOP_RECOVERY)
        }

        listOf(
            failure(PlaybackFailureCategory.DNS, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            failure(PlaybackFailureCategory.HTTP_RESPONSE, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            failure(PlaybackFailureCategory.MANIFEST_FORMAT, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED),
            failure(PlaybackFailureCategory.CODEC_DECODER, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
            failure(PlaybackFailureCategory.UNKNOWN, PlaybackException.ERROR_CODE_UNSPECIFIED),
        ).forEach { failure ->
            assertThat(
                Media3RecoveryDispositionPolicy.disposition(
                    variant = Media3RecoveryPolicyVariant.C_BROAD_RENDER_STOP,
                    failure = failure,
                ),
            ).isEqualTo(PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE)
        }
    }

    private fun failure(
        category: PlaybackFailureCategory,
        media3ErrorCode: Int,
    ) = Media3Failure(
        category = category,
        media3ErrorCode = media3ErrorCode,
    )
}

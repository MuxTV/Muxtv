package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import app.muxtv.player.PlaybackFailureCategory

internal enum class Media3RecoveryPolicyVariant {
    A_CURRENT_GENERIC_NEXT,
    B_NARROW_TYPED_STOP,
    C_BROAD_RENDER_STOP,
}

internal val PRODUCTION_MEDIA3_RECOVERY_POLICY_VARIANT =
    Media3RecoveryPolicyVariant.B_NARROW_TYPED_STOP

@AndroidXOptIn(UnstableApi::class)
internal object Media3RecoveryDispositionPolicy {
    fun disposition(
        variant: Media3RecoveryPolicyVariant,
        failure: Media3Failure,
    ): PlaybackRecoveryDisposition = when (variant) {
        Media3RecoveryPolicyVariant.A_CURRENT_GENERIC_NEXT ->
            PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE

        Media3RecoveryPolicyVariant.B_NARROW_TYPED_STOP ->
            if (failure.media3ErrorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) {
                PlaybackRecoveryDisposition.STOP_RECOVERY
            } else {
                PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE
            }

        Media3RecoveryPolicyVariant.C_BROAD_RENDER_STOP ->
            if (failure.category == PlaybackFailureCategory.PLAYER_RENDER) {
                PlaybackRecoveryDisposition.STOP_RECOVERY
            } else {
                PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE
            }
    }

    fun productionDisposition(failure: Media3Failure): PlaybackRecoveryDisposition =
        disposition(PRODUCTION_MEDIA3_RECOVERY_POLICY_VARIANT, failure)
}

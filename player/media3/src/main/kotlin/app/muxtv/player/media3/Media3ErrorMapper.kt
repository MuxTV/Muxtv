package app.muxtv.player.media3

import androidx.media3.common.PlaybackException
import app.muxtv.player.PlaybackError
import app.muxtv.player.PlaybackErrorCode

internal object Media3ErrorMapper {
    fun fromException(exception: PlaybackException): PlaybackError = fromCode(exception.errorCode)

    fun fromCode(errorCode: Int): PlaybackError = when (
        Media3FailureClassifier.classify(errorCode).category
    ) {
        app.muxtv.player.PlaybackFailureCategory.TIMEOUT -> PlaybackError(
            code = PlaybackErrorCode.NETWORK_TIMEOUT,
            message = "Источник не ответил вовремя",
            retryable = true,
        )

        app.muxtv.player.PlaybackFailureCategory.DNS,
        app.muxtv.player.PlaybackFailureCategory.TLS,
        app.muxtv.player.PlaybackFailureCategory.NETWORK_UNREACHABLE,
        -> PlaybackError(
            code = PlaybackErrorCode.NETWORK_UNREACHABLE,
            message = "Не удалось подключиться к источнику",
            retryable = true,
        )

        app.muxtv.player.PlaybackFailureCategory.HTTP_RESPONSE,
        app.muxtv.player.PlaybackFailureCategory.REDIRECT_POLICY,
        app.muxtv.player.PlaybackFailureCategory.CREDENTIAL_ACCESS,
        -> PlaybackError(
            code = PlaybackErrorCode.HTTP_REJECTED,
            message = "Источник отклонил запрос",
            retryable = false,
        )

        app.muxtv.player.PlaybackFailureCategory.MANIFEST_FORMAT -> PlaybackError(
            code = PlaybackErrorCode.UNSUPPORTED_FORMAT,
            message = "Формат потока не поддерживается устройством",
            retryable = false,
        )

        app.muxtv.player.PlaybackFailureCategory.CODEC_DECODER ->
            if (errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
                PlaybackError(
                    code = PlaybackErrorCode.UNSUPPORTED_FORMAT,
                    message = "Формат потока не поддерживается устройством",
                    retryable = false,
                )
            } else {
                PlaybackError(
                    code = PlaybackErrorCode.DECODER_FAILED,
                    message = "Не удалось запустить декодер",
                    retryable = false,
                )
            }

        app.muxtv.player.PlaybackFailureCategory.PLAYER_RENDER -> PlaybackError(
            code = PlaybackErrorCode.DECODER_FAILED,
            message = "Не удалось запустить декодер",
            retryable = false,
        )

        app.muxtv.player.PlaybackFailureCategory.UNKNOWN -> PlaybackError(
            code = PlaybackErrorCode.UNKNOWN,
            message = "Неизвестная ошибка воспроизведения",
            retryable = false,
        )
    }
}

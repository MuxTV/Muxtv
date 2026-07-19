package app.muxtv.player.media3

import androidx.media3.common.PlaybackException
import app.muxtv.player.PlaybackError
import app.muxtv.player.PlaybackErrorCode

internal object Media3ErrorMapper {
    fun fromException(exception: PlaybackException): PlaybackError = fromCode(exception.errorCode)

    fun fromCode(errorCode: Int): PlaybackError = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackError(
            code = PlaybackErrorCode.NETWORK_TIMEOUT,
            message = "Источник не ответил вовремя",
            retryable = true,
        )

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackError(
            code = PlaybackErrorCode.NETWORK_UNREACHABLE,
            message = "Не удалось подключиться к источнику",
            retryable = true,
        )

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
        -> PlaybackError(
            code = PlaybackErrorCode.HTTP_REJECTED,
            message = "Источник отклонил запрос",
            retryable = false,
        )

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackError(
            code = PlaybackErrorCode.UNSUPPORTED_FORMAT,
            message = "Формат потока не поддерживается устройством",
            retryable = false,
        )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> PlaybackError(
            code = PlaybackErrorCode.DECODER_FAILED,
            message = "Не удалось запустить декодер",
            retryable = false,
        )

        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> PlaybackError(
            code = PlaybackErrorCode.NETWORK_UNREACHABLE,
            message = "Ошибка чтения потока",
            retryable = true,
        )

        else -> PlaybackError(
            code = PlaybackErrorCode.UNKNOWN,
            message = "Неизвестная ошибка воспроизведения",
            retryable = false,
        )
    }
}

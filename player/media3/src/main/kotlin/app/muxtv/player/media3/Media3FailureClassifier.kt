package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.muxtv.player.PlaybackFailureCategory
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal data class Media3Failure(
    val category: PlaybackFailureCategory,
    val httpStatusCode: Int? = null,
    val media3ErrorCode: Int,
)

@AndroidXOptIn(UnstableApi::class)
internal object Media3FailureClassifier {
    fun classify(exception: PlaybackException): Media3Failure {
        return classify(exception.errorCode, exception)
    }

    fun classify(errorCode: Int, cause: Throwable? = null): Media3Failure {
        findCause<UnknownHostException>(cause)?.let {
            return failure(errorCode, PlaybackFailureCategory.DNS)
        }
        findCause<SSLException>(cause)?.let {
            return failure(errorCode, PlaybackFailureCategory.TLS)
        }
        findCause<HttpDataSource.InvalidResponseCodeException>(cause)?.let { response ->
            return Media3Failure(
                category = PlaybackFailureCategory.HTTP_RESPONSE,
                httpStatusCode = response.responseCode,
                media3ErrorCode = errorCode,
            )
        }
        return when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                failure(errorCode, PlaybackFailureCategory.TIMEOUT)
            PlaybackException.ERROR_CODE_TIMEOUT ->
                failure(errorCode, PlaybackFailureCategory.TIMEOUT)
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                failure(errorCode, PlaybackFailureCategory.REDIRECT_POLICY)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> failure(errorCode, PlaybackFailureCategory.NETWORK_UNREACHABLE)
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> failure(errorCode, PlaybackFailureCategory.HTTP_RESPONSE)
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED ->
                failure(errorCode, PlaybackFailureCategory.CREDENTIAL_ACCESS)
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> failure(errorCode, PlaybackFailureCategory.MANIFEST_FORMAT)
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            -> failure(errorCode, PlaybackFailureCategory.CODEC_DECODER)
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            -> failure(errorCode, PlaybackFailureCategory.PLAYER_RENDER)
            else -> failure(errorCode, PlaybackFailureCategory.UNKNOWN)
        }
    }

    private fun failure(
        errorCode: Int,
        category: PlaybackFailureCategory,
    ) = Media3Failure(category = category, media3ErrorCode = errorCode)

    private inline fun <reified T : Throwable> findCause(root: Throwable?): T? {
        var current: Throwable? = root
        repeat(MAX_CAUSE_DEPTH) {
            if (current is T) return current
            val next = current?.cause
            if (next === current) return null
            current = next
        }
        return null
    }

    private const val MAX_CAUSE_DEPTH = 8
}

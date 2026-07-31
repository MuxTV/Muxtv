package app.muxtv.catalog.refresh

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgPayloadDecoderLimitTest {
    @Test
    fun `skip cannot bypass decoded byte limit`() = runTest {
        val result = EpgPayloadDecoder().decode(
            input = ByteArrayInputStream(ByteArray(9) { 1 }),
            limits = EpgPayloadDecodeLimits(maxDecodedBytes = 8),
        ) { input ->
            input.skip(9)
        }

        assertThat(result).isEqualTo(
            EpgPayloadDecodeResult.Rejected(
                EpgPayloadRejectionReason.DecodedSizeExceeded,
            ),
        )
    }

    @Test
    fun `exact decoded byte limit is accepted`() = runTest {
        val result = EpgPayloadDecoder().decode(
            input = ByteArrayInputStream(ByteArray(8) { 1 }),
            limits = EpgPayloadDecodeLimits(maxDecodedBytes = 8),
        ) { input ->
            input.readBytes().size
        }

        assertThat(result).isEqualTo(
            EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Plain, 8),
        )
    }
}
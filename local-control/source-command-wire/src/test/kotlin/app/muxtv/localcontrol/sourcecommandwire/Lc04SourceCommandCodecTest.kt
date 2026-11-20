package app.muxtv.localcontrol.sourcecommandwire

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Lc04SourceCommandCodecTest {
    private val wire = Lc04SourceCommandCodec

    @Test fun validM3uAndCancelHaveCanonicalExactFrames() {
        val m3u = Lc04SourceCommand.PrepareM3u(1, "https://provider.invalid/m3u?secret=abc")
        val encoded = checkNotNull(wire.encodeRequest(m3u))
        val decoded = wire.decodeRequest(encoded) as Lc04SourceCommand.PrepareM3u
        assertThat(decoded.sequence).isEqualTo(1)
        assertThat(decoded.locator).isEqualTo(m3u.locator)
        assertThat(decoded.toString()).doesNotContain("secret")
        assertThat(wire.encodeRequest(decoded)).isEqualTo(encoded)

        val cancel = checkNotNull(wire.encodeRequest(Lc04SourceCommand.Cancel(2)))
        assertThat(cancel.size).isEqualTo(16)
        assertThat(wire.decodeRequest(cancel)).isInstanceOf(Lc04SourceCommand.Cancel::class.java)
        assertThat((wire.decodeRequest(cancel) as Lc04SourceCommand.Cancel).sequence)
            .isEqualTo(2)
    }

    @Test fun xtreamIsBoundedUnicodeSafeAndRedacted() {
        val command = Lc04SourceCommand.PrepareXtream(
            4, "https://provider.invalid", "пользователь", "super-secret-credential",
        )
        val encoded = checkNotNull(wire.encodeRequest(command))
        val decoded = wire.decodeRequest(encoded) as Lc04SourceCommand.PrepareXtream
        assertThat(decoded.endpoint).isEqualTo(command.endpoint)
        assertThat(decoded.username).isEqualTo(command.username)
        assertThat(decoded.password).isEqualTo(command.password)
        assertThat(decoded.toString()).doesNotContain(command.password)
        assertThat(decoded.toString()).doesNotContain(command.username)
        assertThat(wire.encodeRequest(decoded)).isEqualTo(encoded)
    }

    @Test fun maximumAllowedXtreamFieldsFitBound() {
        val frame = wire.encodeRequest(Lc04SourceCommand.PrepareXtream(
            1, "e".repeat(4_096), "u".repeat(256), "p".repeat(2_048),
        ))
        assertThat(frame).isNotNull()
        assertThat(checkNotNull(frame).size).isAtMost(wire.MAX_REQUEST_BYTES)
        assertThat(wire.decodeRequest(checkNotNull(frame)))
            .isInstanceOf(Lc04SourceCommand.PrepareXtream::class.java)
        assertThat(wire.encodeRequest(Lc04SourceCommand.PrepareXtream(
            1, "e".repeat(4_097), "u", "p",
        ))).isNull()
        assertThat(wire.encodeRequest(Lc04SourceCommand.PrepareXtream(
            1, "e", "u", "p".repeat(2_049),
        ))).isNull()
    }

    @Test fun sourceNameCannotCarryControlsOrWhitespacePadding() {
        assertThat(wire.encodeRequest(Lc04SourceCommand.Activate(1, " Каналы"))).isNull()
        assertThat(wire.encodeRequest(Lc04SourceCommand.Activate(1, "Каналы\n"))).isNull()
        val cmd = Lc04SourceCommand.Activate(1, "Семейное ТВ")
        val decoded = wire.decodeRequest(checkNotNull(wire.encodeRequest(cmd)))
            as Lc04SourceCommand.Activate
        assertThat(decoded.sourceName).isEqualTo(cmd.sourceName)
    }

    @Test fun malformedUtf8AndUnpairedSurrogateFailClosed() {
        assertThat(wire.encodeRequest(Lc04SourceCommand.PrepareM3u(1, "\uD800"))).isNull()
        val valid = checkNotNull(wire.encodeRequest(
            Lc04SourceCommand.PrepareM3u(1, "a"),
        ))
        // 16-byte header + 2-byte unsigned field length.
        valid[18] = 0xC3.toByte()
        assertThat(wire.decodeRequest(valid)).isNull()
    }

    @Test fun unknownVersionOpcodeFlagsAndReservedBitsAreRejected() {
        val valid = checkNotNull(wire.encodeRequest(
            Lc04SourceCommand.PrepareM3u(1, "https://provider.invalid"),
        ))
        for ((index, value) in listOf(
            0 to 0x00, 4 to 2, 5 to 0x55, 6 to 1, 7 to 1,
        )) {
            val altered = valid.clone()
            altered[index] = value.toByte()
            assertThat(wire.decodeRequest(altered)).isNull()
        }
    }

    @Test fun partialOversizedTrailingAndZeroSequenceFramesFailClosed() {
        val valid = checkNotNull(wire.encodeRequest(
            Lc04SourceCommand.PrepareM3u(1, "https://provider.invalid"),
        ))
        assertThat(wire.decodeRequest(valid.copyOf(valid.size - 1))).isNull()
        assertThat(wire.decodeRequest(valid + byteArrayOf(0))).isNull()
        assertThat(wire.decodeRequest(ByteArray(wire.MAX_REQUEST_BYTES + 1))).isNull()
        val zero = valid.clone()
        for (i in 8 until 16) zero[i] = 0
        assertThat(wire.decodeRequest(zero)).isNull()
        assertThat(wire.encodeRequest(Lc04SourceCommand.Cancel(0))).isNull()
    }

    @Test fun fieldCountAndUnknownTrailingFlagsAreNeverAccepted() {
        val valid = checkNotNull(wire.encodeRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(wire.decodeRequest(valid + byteArrayOf(0, 0))).isNull()
        val m3u = checkNotNull(wire.encodeRequest(Lc04SourceCommand.PrepareM3u(1, "abc")))
        val altered = m3u.clone()
        altered[16] = 0x10
        altered[17] = 0x01 // 4097, above URL field bound
        assertThat(wire.decodeRequest(altered)).isNull()
    }

    @Test fun replyCodesAreSecretFreeAndCanonical() {
        for (code in Lc04SourceReplyCode.entries) {
            val encoded = checkNotNull(wire.encodeReply(Lc04SourceReply(6, code)))
            assertThat(encoded.size).isEqualTo(16)
            val parsed = checkNotNull(wire.decodeReply(encoded))
            assertThat(parsed.sequence).isEqualTo(6)
            assertThat(parsed.code).isEqualTo(code)
            assertThat(parsed.toString()).doesNotContain("provider")
        }
        val valid = checkNotNull(wire.encodeReply(
            Lc04SourceReply(7, Lc04SourceReplyCode.ACTIVATED),
        ))
        assertThat(wire.decodeReply(valid + byteArrayOf(0))).isNull()
        val corrupt = valid.clone()
        corrupt[5] = 0x7f
        assertThat(wire.decodeReply(corrupt)).isNull()
        corrupt[5] = 2
        corrupt[7] = 1
        assertThat(wire.decodeReply(corrupt)).isNull()
    }
}

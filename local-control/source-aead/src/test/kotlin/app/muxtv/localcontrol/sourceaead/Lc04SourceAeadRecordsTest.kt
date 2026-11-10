package app.muxtv.localcontrol.sourceaead

import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

class Lc04SourceAeadRecordsTest {
    private fun pair(
        now: () -> Long = { 1_000L },
        phoneRoute: () -> Boolean = { true },
        tvRoute: () -> Boolean = { true },
    ) = Lc04SourceAeadRecords.isolatedHostPairForTests(now, phoneRoute, tvRoute)

    @Test fun m3uAndXtreamAreEncryptedAndAuthenticatedEndToEndInMemory() {
        val (phone, tv) = pair()
        phone.use { p -> tv.use { t ->
            val one = checkNotNull(p.sealRequest(
                Lc04SourceCommand.PrepareM3u(1, "https://provider.invalid?token=secret-m3u"),
            ))
            assertThat(String(one, Charsets.ISO_8859_1)).doesNotContain("secret-m3u")
            val requestOne = t.openRequest(one) as Lc04SourceCommand.PrepareM3u
            assertThat(requestOne.locator).contains("secret-m3u")
            val firstReply = checkNotNull(t.sealReply(
                Lc04SourceReply(1, Lc04SourceReplyCode.PREPARED),
            ))
            assertThat(p.openReply(firstReply)?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)

            val two = checkNotNull(p.sealRequest(
                Lc04SourceCommand.PrepareXtream(
                    2, "https://xtream.invalid", "sensitive-user", "secret-password",
                ),
            ))
            assertThat(String(two, Charsets.ISO_8859_1)).doesNotContain("secret-password")
            val requestTwo = t.openRequest(two) as Lc04SourceCommand.PrepareXtream
            assertThat(requestTwo.username).isEqualTo("sensitive-user")
            assertThat(requestTwo.password).isEqualTo("secret-password")
            val secondReply = checkNotNull(t.sealReply(
                Lc04SourceReply(2, Lc04SourceReplyCode.ALREADY_PREPARED),
            ))
            assertThat(p.openReply(secondReply)?.code)
                .isEqualTo(Lc04SourceReplyCode.ALREADY_PREPARED)
            assertThat(p.toString()).doesNotContain("secret")
            assertThat(t.toString()).doesNotContain("secret")
        } }
    }

    @Test fun requestTamperClosesKeysAndPreventsRetry() {
        val (phone, tv) = pair()
        val frame = checkNotNull(phone.sealRequest(
            Lc04SourceCommand.PrepareM3u(1, "https://provider.invalid"),
        ))
        val hostile = frame.clone()
        hostile[hostile.lastIndex] = (hostile.last().toInt() xor 1).toByte()
        assertThat(tv.openRequest(hostile)).isNull()
        assertThat(tv.openRequest(frame)).isNull()
        assertThat(tv.sealReply(Lc04SourceReply(1, Lc04SourceReplyCode.PREPARED))).isNull()
    }

    @Test fun replyTamperClosesPhoneKeyAndRejectsLegitimateResponse() {
        val (phone, tv) = pair()
        val request = checkNotNull(phone.sealRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(tv.openRequest(request)).isInstanceOf(Lc04SourceCommand.Cancel::class.java)
        val reply = checkNotNull(tv.sealReply(
            Lc04SourceReply(1, Lc04SourceReplyCode.CANCELED),
        ))
        val hostile = reply.clone()
        hostile[hostile.lastIndex] = (hostile.last().toInt() xor 0x10).toByte()
        assertThat(phone.openReply(hostile)).isNull()
        assertThat(phone.openReply(reply)).isNull()
    }

    @Test fun replayedRequestIsTerminalAndCanNeverRunSecondCommand() {
        val (phone, tv) = pair()
        val request = checkNotNull(phone.sealRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(tv.openRequest(request)).isNotNull()
        val reply = checkNotNull(tv.sealReply(
            Lc04SourceReply(1, Lc04SourceReplyCode.EMPTY),
        ))
        assertThat(phone.openReply(reply)?.code).isEqualTo(Lc04SourceReplyCode.EMPTY)
        assertThat(tv.openRequest(request)).isNull()
        assertThat(tv.openRequest(checkNotNull(phone.sealRequest(
            Lc04SourceCommand.Cancel(2),
        )))).isNull()
    }

    @Test fun reflectedRequestCannotAuthenticateAsTvReply() {
        val (phone, _) = pair()
        val request = checkNotNull(phone.sealRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(phone.openReply(request)).isNull()
        assertThat(phone.openReply(request)).isNull()
    }

    @Test fun wrongSessionBindingRejectsOtherwiseValidGcm() {
        val (phoneA, _) = pair()
        val (_, tvB) = pair()
        val request = checkNotNull(phoneA.sealRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(tvB.openRequest(request)).isNull()
        assertThat(tvB.openRequest(request)).isNull()
    }

    @Test fun headerVersionDirectionLengthFlagsAndNonceAreAllAuthenticated() {
        val offsets = intArrayOf(0, 4, 5, 6, 7, 8, 39, 40, 47, 48, 49, 50, 61)
        for (at in offsets) {
            val (p, t) = pair()
            val legitimate = checkNotNull(p.sealRequest(Lc04SourceCommand.Cancel(1)))
            val altered = legitimate.clone()
            altered[at] = (altered[at].toInt() xor 0x20).toByte()
            assertThat(t.openRequest(altered)).isNull()
            assertThat(t.openRequest(legitimate)).isNull()
        }
    }

    @Test fun truncationAndPaddingAreRejectedTerminally() {
        for (mutation in 0..2) {
            val (p, t) = pair()
            val frame = checkNotNull(p.sealRequest(Lc04SourceCommand.Cancel(1)))
            val hostile = when (mutation) {
                0 -> frame.copyOf(frame.size - 1)
                1 -> frame + byteArrayOf(0)
                else -> byteArrayOf(0)
            }
            assertThat(t.openRequest(hostile)).isNull()
            assertThat(t.openRequest(frame)).isNull()
        }
    }

    @Test fun firstSequenceMustBeOneAndNoSecondInFlightIsAllowed() {
        val (p, t) = pair()
        assertThat(p.sealRequest(Lc04SourceCommand.Cancel(2))).isNull()
        assertThat(p.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
        assertThat(t.openRequest(ByteArray(0))).isNull()

        val (p2, t2) = pair()
        assertThat(p2.sealRequest(Lc04SourceCommand.Cancel(1))).isNotNull()
        assertThat(p2.sealRequest(Lc04SourceCommand.Cancel(2))).isNull()
        assertThat(t2.sealReply(Lc04SourceReply(1, Lc04SourceReplyCode.PREPARED))).isNull()
    }

    @Test fun wrongReplySequenceIsNeverEncryptedAndClosesSession() {
        val (p, t) = pair()
        assertThat(t.openRequest(checkNotNull(
            p.sealRequest(Lc04SourceCommand.Cancel(1)),
        ))).isNotNull()
        assertThat(t.sealReply(Lc04SourceReply(2, Lc04SourceReplyCode.EMPTY))).isNull()
        assertThat(t.sealReply(Lc04SourceReply(1, Lc04SourceReplyCode.EMPTY))).isNull()
    }

    @Test fun expiryClockRollbackAndRouteRevocationZeroizeTerminally() {
        val clock = AtomicLong(1_000)
        val (phone, _) = pair(now = clock::get)
        clock.set(121_000)
        assertThat(phone.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()

        val reverseClock = AtomicLong(1_000)
        val (p2, _) = pair(now = reverseClock::get)
        reverseClock.set(1_010)
        val request = checkNotNull(p2.sealRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(request).isNotEmpty()
        reverseClock.set(1_009)
        assertThat(p2.openReply(ByteArray(0))).isNull()

        var route = true
        val (p3, _) = pair(phoneRoute = { route })
        route = false
        assertThat(p3.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
        route = true
        assertThat(p3.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
    }

    @Test fun closeIsIrreversibleAndDoesNotLeakSecrets() {
        val (p, t) = pair()
        p.close()
        p.close()
        t.close()
        t.close()
        assertThat(p.sealRequest(Lc04SourceCommand.PrepareXtream(
            1, "https://provider.invalid", "secret-u", "secret-p",
        ))).isNull()
        assertThat(t.openRequest(ByteArray(100))).isNull()
        assertThat(p.toString()).isEqualTo("Lc04SourceAeadRecords([REDACTED])")
    }

    @Test fun sixteenRequestReplyPairsAreValidButSeventeenthIsTerminal() {
        val (p, t) = pair()
        for (i in 1L..16L) {
            val wire = checkNotNull(p.sealRequest(Lc04SourceCommand.Cancel(i)))
            val request = t.openRequest(wire) as Lc04SourceCommand.Cancel
            assertThat(request.sequence).isEqualTo(i)
            val reply = checkNotNull(t.sealReply(
                Lc04SourceReply(i, Lc04SourceReplyCode.EMPTY),
            ))
            assertThat(p.openReply(reply)?.sequence).isEqualTo(i)
        }
        assertThat(p.sealRequest(Lc04SourceCommand.Cancel(17))).isNull()
        assertThat(t.openRequest(ByteArray(0))).isNull()
    }

    @Test fun recordNonceIsUniquePerDirectionAndBoundToSequence() {
        val (p, t) = pair()
        val first = checkNotNull(p.sealRequest(Lc04SourceCommand.Cancel(1)))
        assertThat(t.openRequest(first)).isNotNull()
        val reply = checkNotNull(t.sealReply(
            Lc04SourceReply(1, Lc04SourceReplyCode.EMPTY),
        ))
        val next = checkNotNull(p.openReply(reply))
        assertThat(next.sequence).isEqualTo(1L)
        val second = checkNotNull(p.sealRequest(Lc04SourceCommand.Cancel(2)))
        val oneNonce = first.copyOfRange(50, 62)
        val twoNonce = second.copyOfRange(50, 62)
        assertThat(oneNonce.contentEquals(twoNonce)).isFalse()
        assertThat(ByteBuffer.wrap(oneNonce).order(ByteOrder.BIG_ENDIAN).getLong(4))
            .isEqualTo(1L)
        assertThat(ByteBuffer.wrap(twoNonce).order(ByteOrder.BIG_ENDIAN).getLong(4))
            .isEqualTo(2L)
    }
}

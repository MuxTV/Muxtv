package app.muxtv.localcontrol.sourceaead

import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Only KDF unit tests. They do NOT attest the original Ed25519/HPKE handshake.
 * Original-owner tests live in tv-qr/TvOriginalMxH1SourceKeyHandoffTest.
 */
class Lc04SourceAppKeyCandidateTest {
    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val transcript = ByteArray(32) { (it + 81).toByte() }
    private val sixth = ByteArray(104) { (it xor 0x67).toByte() }

    private fun candidate(secret: ByteArray = seed, digest: ByteArray = transcript) =
        Lc04SourceAppKeyCandidate.prepareFromOriginalHpkeSeed(secret, digest)

    private fun port(
        candidate: Lc04SourceAppKeyCandidate,
        frame: ByteArray = sixth,
        phoneSide: Boolean,
    ) = candidate.finishAfterAuthenticatedSixth(
        frame, phoneSide, { 1_000L },
    )

    @Test fun matchingSourceExportCandidatesAgreeButCannotBeReused() {
        val phoneCandidate = candidate()
        val tvCandidate = candidate()
        val phone = checkNotNull(port(phoneCandidate, phoneSide = true))
        val tv = checkNotNull(port(tvCandidate, phoneSide = false))
        val encrypted = checkNotNull(phone.sealRequest(
            Lc04SourceCommand.PrepareM3u(1, "https://example.invalid/secret"),
        ))
        val decrypted = tv.openRequest(encrypted) as Lc04SourceCommand.PrepareM3u
        assertThat(decrypted.locator).isEqualTo("https://example.invalid/secret")
        val reply = checkNotNull(tv.sealReply(
            Lc04SourceReply(1, Lc04SourceReplyCode.PREPARED),
        ))
        assertThat(phone.openReply(reply)?.code).isEqualTo(Lc04SourceReplyCode.PREPARED)
        assertThat(port(phoneCandidate, phoneSide = true)).isNull()
        assertThat(port(tvCandidate, phoneSide = false)).isNull()
        phone.close()
        tv.close()
    }

    @Test fun mismatchedSixthSecretOrTranscriptNeverAuthenticates() {
        for (mismatch in 0..2) {
            val phone = checkNotNull(port(candidate(), phoneSide = true))
            val otherFrame = sixth.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val otherSeed = seed.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val otherTranscript = transcript.clone().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            }
            val tv = checkNotNull(when (mismatch) {
                0 -> port(candidate(), otherFrame, phoneSide = false)
                1 -> port(candidate(otherSeed), phoneSide = false)
                else -> port(candidate(digest = otherTranscript), phoneSide = false)
            })
            val request = checkNotNull(phone.sealRequest(Lc04SourceCommand.Cancel(1)))
            assertThat(tv.openRequest(request)).isNull()
            assertThat(tv.openRequest(request)).isNull()
            phone.close()
            tv.close()
        }
    }

    @Test fun invalidInputAndEarlyCloseAreTerminal() {
        assertThat(runCatching { candidate(ByteArray(32)) }.isFailure).isTrue()
        assertThat(runCatching { candidate(seed.copyOf(31)) }.isFailure).isTrue()
        assertThat(runCatching {
            candidate(digest = transcript.copyOf(31))
        }.isFailure).isTrue()
        val invalid = candidate()
        assertThat(port(invalid, sixth.copyOf(103), phoneSide = true)).isNull()
        assertThat(port(invalid, phoneSide = true)).isNull()
        val closed = candidate()
        closed.close()
        assertThat(port(closed, phoneSide = false)).isNull()
        assertThat(closed.toString()).doesNotContain("secret")
    }

    @Test fun failedRouteRevokesClaimedSourcePort() {
        var eligible = true
        val c = candidate()
        val records = checkNotNull(c.finishAfterAuthenticatedSixth(
            sixth, phoneSide = true, now = { 1_000L },
            routeEligible = { eligible },
        ))
        eligible = false
        assertThat(records.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
        eligible = true
        assertThat(records.sealRequest(Lc04SourceCommand.Cancel(1))).isNull()
    }
}

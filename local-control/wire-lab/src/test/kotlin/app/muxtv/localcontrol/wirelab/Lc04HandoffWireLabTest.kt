package app.muxtv.localcontrol.wirelab

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Test

/** MXH1 structural lab only: no application channel or real network handoff. */
class Lc04HandoffWireLabTest {
    private val invitation = ByteArray(16) { (it + 1).toByte() }
    private val pairing = ByteArray(16) { (it + 17).toByte() }
    private val tv = ByteArray(32) { (it + 33).toByte() }
    private val phone = ByteArray(32) { (it + 65).toByte() }

    private fun frame(
        kind: Lc04HandoffKind,
        payload: ByteArray,
        counter: Int = 1,
        digest: ByteArray = ByteArray(32),
        invitationId: ByteArray = invitation,
        pairingId: ByteArray = pairing,
        tvKey: ByteArray = tv,
        phoneKey: ByteArray = phone,
    ) = Lc04HandoffFrame(
        kind, invitationId, pairingId, tvKey, phoneKey, digest, counter, payload,
    )

    private fun offer(
        signatureLength: Int = 69,
        ciphertextLength: Int = 111,
        counter: Int = 1,
    ): ByteArray = Lc04HandoffWireLab.encode(frame(
        Lc04HandoffKind.SIGNED_SEED_OFFER,
        Lc04HandoffWireLab.signedOfferBody(
            ByteArray(ciphertextLength) { (it * 19).toByte() },
            ByteArray(signatureLength) { (it * 7).toByte() },
        ),
        counter,
    ))

    private fun confirmation(
        originalOffer: ByteArray,
        kind: Lc04HandoffKind,
        counter: Int = checkNotNull(Lc04HandoffWireLab.decode(originalOffer)).counter,
        digest: ByteArray = checkNotNull(Lc04HandoffWireLab.digestOfSignedOffer(originalOffer)),
        invitationId: ByteArray = invitation,
        pairingId: ByteArray = pairing,
        tvKey: ByteArray = tv,
        phoneKey: ByteArray = phone,
    ): ByteArray {
        val payload = when (kind) {
            Lc04HandoffKind.TV_KEY_CONFIRM ->
                Lc04HandoffWireLab.tvConfirmationBody(
                    ByteArray(32) { 11 }, ByteArray(32) { 12 },
                )
            Lc04HandoffKind.PHONE_KEY_CONFIRM -> ByteArray(32) { 13 }
            else -> error("A confirmation kind is required")
        }
        return Lc04HandoffWireLab.encode(frame(
            kind, payload, counter, digest, invitationId, pairingId, tvKey, phoneKey,
        ))
    }

    @Test fun fourthTvFinishedFrameIsCanonicalBoundedAndNeverAuthenticatedByCodec() {
        val signedOffer = offer()
        val digest = checkNotNull(Lc04HandoffWireLab.digestOfSignedOffer(signedOffer))
        val forgedTag = ByteArray(32) { 7 }
        val finished = Lc04HandoffWireLab.encode(frame(
            Lc04HandoffKind.TV_FINISHED, forgedTag, digest = digest,
        ))
        assertThat(finished.size).isEqualTo(138 + 32)
        assertThat(Lc04HandoffWireLab.decode(finished)?.kind)
            .isEqualTo(Lc04HandoffKind.TV_FINISHED)
        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
            signedOffer, finished, Lc04HandoffKind.TV_FINISHED,
        )).isTrue()
        // This is intentionally UNAUTHENTICATED; only the separate HMAC
        // verifier may accept TV_FINISHED, never this structural check.
        val tamperedTag = finished.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
            signedOffer, tamperedTag, Lc04HandoffKind.TV_FINISHED,
        )).isTrue()
        assertThat(Lc04HandoffWireLab.offerSigningStatement(finished)).isNull()
        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
            signedOffer, finished, Lc04HandoffKind.TV_KEY_CONFIRM,
        )).isFalse()
        for (wrongSize in listOf(0, 31, 33, 64)) {
            assertThat(runCatching {
                frame(Lc04HandoffKind.TV_FINISHED, ByteArray(wrongSize), digest = digest)
            }.isFailure).isTrue()
        }
        for (index in listOf(6, 22, 38, 70, 102, 135, 137)) {
            val changed = finished.clone()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                signedOffer, changed, Lc04HandoffKind.TV_FINISHED,
            )).isFalse()
        }
        val forbiddenZeroDigest = finished.clone()
        forbiddenZeroDigest.fill(0, 102, 134)
        assertThat(Lc04HandoffWireLab.decode(forbiddenZeroDigest)).isNull()
    }

    @Test fun canonicalMaxAndMinOfferAndConfirmBoundariesRoundTrip() {
        for (sig in listOf(64, 69)) {
            for (cipher in listOf(17, 111, 2048)) {
                for (counter in listOf(1, 128)) {
                    val original = offer(sig, cipher, counter)
                    val decoded = checkNotNull(Lc04HandoffWireLab.decode(original))
                    assertThat(decoded.kind).isEqualTo(Lc04HandoffKind.SIGNED_SEED_OFFER)
                    assertThat(decoded.counter).isEqualTo(counter)
                    assertThat(decoded.offerDigest()).isEqualTo(ByteArray(32))
                    assertThat(Lc04HandoffWireLab.encode(decoded)).isEqualTo(original)
                    val parts = checkNotNull(Lc04HandoffWireLab.decodeSignedOfferBody(
                        decoded.payload(),
                    ))
                    assertThat(parts.first.size).isEqualTo(cipher)
                    assertThat(parts.second.size).isEqualTo(sig)
                    assertThat(original.size).isAtMost(Lc04HandoffWireLab.MAX_FRAME_BYTES)
                    for (kind in listOf(
                        Lc04HandoffKind.TV_KEY_CONFIRM,
                        Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    )) {
                        val confirmed = confirmation(original, kind)
                        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                            original, confirmed, kind,
                        )).isTrue()
                        assertThat(Lc04HandoffWireLab.encode(
                            checkNotNull(Lc04HandoffWireLab.decode(confirmed)),
                        )).isEqualTo(confirmed)
                    }
                }
            }
        }
        assertThat(offer(69, 2048).size).isEqualTo(2258)
        assertThat(offer(64, 17).size).isEqualTo(222)
    }

    @Test fun separateVersionedHandshakeCanNeverBeMisparsedAsOldMxw1() {
        val newFrame = offer()
        assertThat(newFrame.copyOfRange(0, 4)).isEqualTo(
            byteArrayOf(0x4d, 0x58, 0x48, 0x31),
        )
        assertThat(Lc04WireLab.decode(newFrame)).isNull()
        val legacy = Lc04WireLab.encode(
            Lc04WireFrame(Lc04WireKind.PHONE_PUBLIC_KEY,
                invitation, pairing, tv, phone, byteArrayOf(7)),
        )
        assertThat(Lc04HandoffWireLab.decode(legacy)).isNull()
    }

    @Test fun signatureStatementBindsAllPublicFieldsAndSignatureWidthButNotSignatureBytes() {
        val original = offer()
        val statement = checkNotNull(Lc04HandoffWireLab.offerSigningStatement(original))
        val changedSignature = original.clone()
        changedSignature[changedSignature.lastIndex] =
            (changedSignature.last().toInt() xor 1).toByte()
        assertThat(Lc04HandoffWireLab.offerSigningStatement(changedSignature))
            .isEqualTo(statement)
        assertThat(Lc04HandoffWireLab.digestOfSignedOffer(changedSignature))
            .isNotEqualTo(Lc04HandoffWireLab.digestOfSignedOffer(original))
        for (offset in listOf(6, 22, 38, 70, 134, 136, 140, 144, 222)) {
            val changed = original.clone()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            val recomputed = Lc04HandoffWireLab.offerSigningStatement(changed)
            if (recomputed != null) assertThat(recomputed).isNotEqualTo(statement)
        }
        assertThat(Lc04HandoffWireLab.offerSigningStatement(offer(64)))
            .isNotEqualTo(statement)
        assertThat(Lc04HandoffWireLab.offerSigningStatement(
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM),
        )).isNull()
    }

    @Test fun realTinkEd25519CannotVerifyTamperedExactMxH1OfferStatement() {
        SignatureConfig.register()
        val keys = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.generateEntryFromParametersName("ED25519")
                .withRandomId().makePrimary()).build()
        val signer = keys.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)
        val verifier = keys.publicKeysetHandle.getPrimitive(
            RegistryConfiguration.get(), PublicKeyVerify::class.java,
        )
        val draft = offer()
        val statement = checkNotNull(Lc04HandoffWireLab.offerSigningStatement(draft))
        val signature = signer.sign(statement)
        assertThat(signature.size).isEqualTo(69) // TINK output prefix is bound.
        val signed = Lc04HandoffWireLab.encode(frame(
            Lc04HandoffKind.SIGNED_SEED_OFFER,
            Lc04HandoffWireLab.signedOfferBody(ByteArray(111) {
                (it * 19).toByte()
            }, signature),
        ))
        val signedStatement = checkNotNull(Lc04HandoffWireLab.offerSigningStatement(signed))
        verifier.verify(signature, signedStatement)
        assertThat(signedStatement).isEqualTo(statement)
        for (offset in listOf(6, 22, 38, 70, 135, 150)) {
            val changed = signed.clone()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            val forgedStatement = Lc04HandoffWireLab.offerSigningStatement(changed)
            if (forgedStatement != null) {
                assertThat(runCatching { verifier.verify(signature, forgedStatement) }.isFailure)
                    .isTrue()
            }
        }
        val alteredSignature = signed.clone()
        alteredSignature[alteredSignature.lastIndex] =
            (alteredSignature.last().toInt() xor 1).toByte()
        val parsed = checkNotNull(Lc04HandoffWireLab.decode(alteredSignature))
        val changedSignature = checkNotNull(Lc04HandoffWireLab.decodeSignedOfferBody(
            parsed.payload(),
        )).second
        assertThat(runCatching { verifier.verify(changedSignature, signedStatement) }.isFailure)
            .isTrue()
    }

    @Test fun exactSignedOfferDigestCounterAndAllIdentitiesBindEachConfirmation() {
        val original = offer()
        val wanted = confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM)
        val changedSigned = original.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
            changedSigned, wanted, Lc04HandoffKind.TV_KEY_CONFIRM,
        )).isFalse()
        for (changed in listOf(
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM, counter = 2),
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM,
                digest = ByteArray(32) { 9 }),
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM,
                invitationId = ByteArray(16) { 8 }),
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM,
                pairingId = ByteArray(16) { 8 }),
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM,
                tvKey = ByteArray(32) { 8 }),
            confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM,
                phoneKey = ByteArray(32) { 8 }),
            confirmation(original, Lc04HandoffKind.PHONE_KEY_CONFIRM),
        )) {
            assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
                original, changed, Lc04HandoffKind.TV_KEY_CONFIRM,
            )).isFalse()
        }
        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
            original, wanted, Lc04HandoffKind.SIGNED_SEED_OFFER,
        )).isFalse()
    }

    @Test fun structurallyMatchingForgedMacIsNotAuthentication() {
        // Deliberately demonstrate missing authority: this codec CANNOT
        // distinguish a forged 32-byte MAC from the real TV's HMAC.
        val original = offer()
        val forged = confirmation(original, Lc04HandoffKind.TV_KEY_CONFIRM)
        assertThat(Lc04HandoffWireLab.structurallyMatchesOffer(
            original, forged, Lc04HandoffKind.TV_KEY_CONFIRM,
        )).isTrue()
        // Neither decode() nor structurallyMatchesOffer() verifies Tink/HPKE.
    }

    @Test fun untrustedTruncationGarbageVersionsKindsAndLengthMutationsAreRejected() {
        val original = offer()
        for (n in 0 until original.size) {
            assertThat(Lc04HandoffWireLab.decode(original.copyOf(n))).isNull()
        }
        assertThat(Lc04HandoffWireLab.decode(original + byteArrayOf(0))).isNull()
        for ((index, replacement) in listOf(
            0 to 0, 1 to 0, 2 to 0, 3 to 0,
            4 to 0, 4 to 2,
            5 to 0, 5 to 4, 5 to 0x7f, 5 to 0x12,
            134 to 1, 135 to 0, 135 to 129,
            136 to 1, 137 to 0, 136 to 255, 137 to 1,
            138 to 1, 139 to 0,
        )) {
            val changed = original.clone()
            changed[index] = replacement.toByte()
            assertThat(Lc04HandoffWireLab.decode(changed)).isNull()
        }
        assertThat(Lc04HandoffWireLab.decode(ByteArray(5_000))).isNull()
        val noise = Random(10072)
        repeat(500) {
            val junk = ByteArray(noise.nextInt(0, 2700)).also { noise.nextBytes(it) }
            assertThat(Lc04HandoffWireLab.decode(junk)).isNull()
        }
    }

    @Test fun canonicalInnerCipherAndSignatureLengthsRejectForgedLayouts() {
        for (badSig in listOf(0, 63, 65, 68, 70, 255)) {
            assertThat(runCatching {
                Lc04HandoffWireLab.signedOfferBody(ByteArray(34), ByteArray(badSig))
            }.isFailure).isTrue()
        }
        for (badCipher in listOf(0, 16, 2049)) {
            assertThat(runCatching {
                Lc04HandoffWireLab.signedOfferBody(ByteArray(badCipher), ByteArray(69))
            }.isFailure).isTrue()
        }
        val actual = offer()
        for ((index, value) in listOf(
            138 to 1, 139 to 1, 138 to 0x08, 139 to 0x01,
            (138 + 2 + 111) to 0, (138 + 2 + 111) to 68,
        )) {
            val changed = actual.clone()
            changed[index] = value.toByte()
            assertThat(Lc04HandoffWireLab.decode(changed)).isNull()
        }
        val forgedOfferDigest = actual.clone()
        forgedOfferDigest[102] = 1
        assertThat(Lc04HandoffWireLab.decode(forgedOfferDigest)).isNull()
        val confirm = confirmation(actual, Lc04HandoffKind.TV_KEY_CONFIRM)
        val zeroDigest = confirm.clone()
        zeroDigest.fill(0, 102, 134)
        assertThat(Lc04HandoffWireLab.decode(zeroDigest)).isNull()
    }

    @Test fun clonedPayloadsAndTextNeverExposePrivateBytes() {
        val original = offer()
        val frame = checkNotNull(Lc04HandoffWireLab.decode(original))
        val copy = frame.payload()
        copy.fill(0)
        assertThat(frame.payload()).isNotEqualTo(copy)
        assertThat(frame.toString()).contains("[REDACTED]")
        assertThat(frame.toString()).doesNotContain("111")
        assertThat(frame.invitation()).isEqualTo(invitation)
        frame.invitation().fill(0)
        assertThat(frame.invitation()).isEqualTo(invitation)
    }

    @Test fun encodedConfirmationNonceAndMacSizesAreStrict() {
        for (bad in listOf(0, 31, 33, 64)) {
            assertThat(runCatching {
                Lc04HandoffWireLab.tvConfirmationBody(ByteArray(bad), ByteArray(32))
            }.isFailure).isTrue()
            assertThat(runCatching {
                Lc04HandoffWireLab.tvConfirmationBody(ByteArray(32), ByteArray(bad))
            }.isFailure).isTrue()
        }
        assertThat(runCatching {
            frame(Lc04HandoffKind.TV_KEY_CONFIRM, ByteArray(63),
                digest = ByteArray(32) { 7 })
        }.isFailure).isTrue()
        assertThat(runCatching {
            frame(Lc04HandoffKind.PHONE_KEY_CONFIRM, ByteArray(33),
                digest = ByteArray(32) { 7 })
        }.isFailure).isTrue()
    }
}

package app.muxtv.localcontrol.tvqr

import com.google.common.truth.Truth.assertThat
import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class TvHpkeQrSessionTest {
    @Test fun publicQrUsesExactLabWireFormatAndRealX25519PublicKey() {
        val session = TvHpkeQrSession.create(100L)
        val view = session.presentation(100L)!!
        assertThat(view.qrValue).startsWith("MXLC04:1:")
        assertThat(view.qrValue.length).isAtMost(900)
        val fields = view.qrValue.split(':')
        assertThat(fields).hasSize(5)
        assertThat(fields[2]).matches("[0-9a-f]{32}")
        assertThat(fields[3]).matches("[0-9a-f]{32}")
        val bytes = Base64.getUrlDecoder().decode(fields[4])
        assertThat(bytes.size).isAtMost(512)
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            .isEqualTo(fields[4])
        val keyset = Keyset.parseFrom(bytes)
        assertThat(keyset.keyCount).isEqualTo(1)
        assertThat(keyset.primaryKeyId).isEqualTo(keyset.getKey(0).keyId)
        val key = keyset.getKey(0)
        assertThat(key.status).isEqualTo(KeyStatusType.ENABLED)
        assertThat(key.keyData.keyMaterialType).isEqualTo(KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)
        assertThat(key.keyData.typeUrl)
            .isEqualTo("type.googleapis.com/google.crypto.tink.HpkePublicKey")
        val hpke = HpkePublicKey.parseFrom(key.keyData.value)
        assertThat(hpke.version).isEqualTo(0)
        assertThat(hpke.publicKey.size()).isEqualTo(32)
        assertThat(hpke.params.kem).isEqualTo(HpkeKem.DHKEM_X25519_HKDF_SHA256)
        assertThat(hpke.params.kdf).isEqualTo(HpkeKdf.HKDF_SHA256)
        assertThat(hpke.params.aead).isEqualTo(HpkeAead.AES_256_GCM)
        val scanned = LabTvQrKeyBinding.parse(view.qrValue)
        assertThat(scanned.keyFingerprint).isEqualTo(view.fingerprintSha256)
        assertThat(scanned.binding.tvInvitationId).isEqualTo(fields[2])
        assertThat(scanned.binding.approvedPairingId).isEqualTo(fields[3])
        val parsed = TinkProtoKeysetFormat.parseKeysetWithoutSecret(bytes)
        assertThat(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(parsed)).isEqualTo(bytes)
        val encryptor = parsed.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        assertThat(encryptor.encrypt("no secrets".toByteArray(), "context".toByteArray())).isNotEmpty()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertThat(view.fingerprintSha256).isEqualTo(expected)
        assertThat(view.expiresAtElapsedMillis).isEqualTo(120_100L)
        session.close()
    }

    @Test fun freshSessionsHaveDistinctIdsAndKeysAndDoNotExposePrivateMaterial() {
        val first = TvHpkeQrSession.create(1L)
        val second = TvHpkeQrSession.create(1L)
        val a = first.presentation(1L)!!
        val b = second.presentation(1L)!!
        assertThat(a.qrValue).isNotEqualTo(b.qrValue)
        assertThat(a.fingerprintSha256).isNotEqualTo(b.fingerprintSha256)
        assertThat(first.toString()).doesNotContain(a.qrValue)
        assertThat(a.toString()).doesNotContain(a.qrValue)
        first.close()
        second.close()
    }

    @Test fun expirationClockRollbackAndExplicitRevocationFailClosed() {
        val session = TvHpkeQrSession.create(10_000L)
        assertThat(session.presentation(9_999L)).isNull()
        assertThat(session.presentation(10_001L)).isNull() // rollback revokes permanently
        val expiring = TvHpkeQrSession.create(0L)
        assertThat(expiring.presentation(119_999L)).isNotNull()
        assertThat(expiring.presentation(120_000L)).isNull()
        assertThat(expiring.presentation(10L)).isNull() // expiration is irreversible
        val closed = TvHpkeQrSession.create(0L)
        closed.close()
        assertThat(closed.presentation(1L)).isNull()
    }
}

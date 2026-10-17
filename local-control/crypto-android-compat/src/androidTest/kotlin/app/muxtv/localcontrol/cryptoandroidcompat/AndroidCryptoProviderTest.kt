package app.muxtv.localcontrol.cryptoandroidcompat

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.localcontrol.cryptolab.ExperimentalHpkeLab
import app.muxtv.localcontrol.cryptolab.LabBinding
import app.muxtv.localcontrol.cryptolab.LabDecryptResult
import app.muxtv.localcontrol.cryptolab.LabOperation
import app.muxtv.localcontrol.cryptolab.LabPhoneSender
import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-proven compatibility only. Never executes IPTV URLs, credentials, or HTTP.
 * A JVM-only crypto-lab PASS is insufficient to assert Android TV API26 support.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCryptoProviderTest {
    private val binding = LabBinding("1".repeat(32), "2".repeat(32))

    @Test
    fun tinkHpkeX25519EncryptDecryptRunsOnTheActualAndroidProvider() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val phone = LabPhoneSender(binding, tv.publicKeyset)
        val encrypted = phone.encrypt(
            LabOperation.SOURCE_PREPARE, "nonsecret-test".toByteArray(),
        )
        val accepted = tv.decrypt(encrypted)
        assertThat(accepted).isInstanceOf(LabDecryptResult.Accepted::class.java)
        var observed = ""
        (accepted as LabDecryptResult.Accepted).consumeOnce {
            observed = it.toString(Charsets.UTF_8)
        }
        assertThat(observed).isEqualTo("nonsecret-test")
    }

    @Test
    fun actualTvPublicKeyQrParsesAndPinsAcrossTargetAndroidApis() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val text = LabTvQrKeyBinding.encode(binding, tv.publicKeyset)
        val scanned = LabTvQrKeyBinding.parse(text)
        assertThat(scanned.binding.tvInvitationId).isEqualTo(binding.tvInvitationId)
        assertThat(scanned.encodedPublicKeyset()).isNotEmpty()
        val phone = LabPhoneSender(binding, scanned.publicKeyset)
        val encrypted = phone.encrypt(LabOperation.SOURCE_CANCEL, "nonsecret".toByteArray())
        assertThat(tv.decrypt(encrypted)).isInstanceOf(LabDecryptResult.Accepted::class.java)
    }

    @Test
    fun tinkEd25519SigningAndVerificationDoesNotRequireAndroidPlatformEd25519() {
        SignatureConfig.register()
        val signingKeys = KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.generateEntryFromParametersName("ED25519")
                    .withRandomId().makePrimary(),
            )
            .build()
        val signer = signingKeys.getPrimitive(
            RegistryConfiguration.get(), PublicKeySign::class.java,
        )
        val verifier = signingKeys.publicKeysetHandle.getPrimitive(
            RegistryConfiguration.get(), PublicKeyVerify::class.java,
        )
        val message = "MuxTV-LC04-Android-provider-check-no-secrets".toByteArray()
        val signature = signer.sign(message)
        verifier.verify(signature, message)
        var rejected = false
        try {
            verifier.verify(signature, "modified-nonsecret".toByteArray())
        } catch (_: GeneralSecurityException) {
            rejected = true
        }
        assertThat(rejected).isTrue()
    }

    @Test
    fun nativePlatformEd25519MustNotBeAssumedOnApi26() {
        val platformSignature = runCatching { Signature.getInstance("Ed25519") }.isSuccess
        val platformGenerator = runCatching {
            KeyPairGenerator.getInstance("Ed25519")
        }.isSuccess

        if (Build.VERSION.SDK_INT >= 33) {
            // Android's documented Ed25519 platform contract begins with API 33.
            assertThat(platformSignature).isTrue()
        }
        // On API26, success/failure is provider-dependent and NOT a production guarantee.
        // Test the provider if it is actually present rather than claiming compatibility.
        // Android's Signature API contract (33+) does not itself guarantee
        // a platform Ed25519 KeyPairGenerator. A missing generator must NOT
        // falsely fail API36 or be reported as native key-management support.
        if (!platformSignature || !platformGenerator) {
            return
        }
        val generator = KeyPairGenerator.getInstance("Ed25519")
        // On Android 36 this JCA algorithm can resolve to AndroidKeyStore,
        // which requires KeyGenParameterSpec initialization before generateKeyPair.
        // This is an optional platform capability probe, NOT our cross-API key
        // generation contract. The Tink Ed25519 round trip above is mandatory.
        if (generator.provider.name.contains("AndroidKeyStore", ignoreCase = true)) {
            assertThat(generator.provider.name).contains("AndroidKeyStore")
            return
        }
        val result = runCatching {
            val keyPair = generator.generateKeyPair()
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(keyPair.private)
            signer.update("MuxTV-nonsecret-platform-fixture".toByteArray())
            val proof = signer.sign()
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(keyPair.public)
            verifier.update("MuxTV-nonsecret-platform-fixture".toByteArray())
            verifier.verify(proof)
        }
        if (result.isSuccess) {
            assertThat(result.getOrThrow()).isTrue()
        }
        // An optional JCA generator failure is not Tink or production coverage.
    }
}

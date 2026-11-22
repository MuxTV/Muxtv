package app.muxtv.localcontrol.cryptolab

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.GeneralSecurityException

/**
 * LC04-S2 laboratory, NOT an authenticated network transport.
 *
 * Uses Google's RFC 9180 HPKE primitive. The sender MUST already trust the TV public
 * key from an independent QR, and the page itself MUST be loaded from an authenticated
 * secure origin. HPKE in base mode does NOT authenticate the phone.
 *
 * This module is deliberately NOT a dependency of app:tv, local-control:server or
 * local-control:onboarding. No production API can use this prototype.
 */
object ExperimentalHpkeLab {
    private const val ALGORITHM = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    fun newReceiver(binding: LabBinding): LabReceiver {
        HybridConfig.register()
        val keys = KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.generateEntryFromParametersName(ALGORITHM)
                    .withRandomId()
                    .makePrimary(),
            ).build()
        val decryptor = keys.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        return LabReceiver(binding, keys.publicKeysetHandle, decryptor)
    }
}

/** Identifiers are fixed hexadecimal values, never raw bearer or provider secrets. */
class LabBinding(
    val tvInvitationId: String,
    val approvedPairingId: String,
) {
    init {
        require(tvInvitationId.matches(Regex("[0-9a-f]{32}")))
        require(approvedPairingId.matches(Regex("[0-9a-f]{32}")))
    }

    internal fun context(operation: LabOperation, counter: Int): ByteArray =
        ("MuxTV-LC04-HPKE-LAB-v1|$tvInvitationId|$approvedPairingId|phone-to-tv|" +
            "${operation.name}|$counter").toByteArray(Charsets.US_ASCII)

    override fun toString(): String = "LabBinding([REDACTED])"
}

enum class LabOperation { SOURCE_PREPARE, SOURCE_ACTIVATE, SOURCE_CANCEL, CHANNEL_PROBE }

class LabCiphertext(
    val operation: LabOperation,
    val counter: Int,
    ciphertext: ByteArray,
) {
    internal val bytes: ByteArray = ciphertext.clone()
    override fun toString(): String = "LabCiphertext(operation=$operation, counter=$counter, ciphertext=[REDACTED])"
}

/** In-memory phone fixture only. Real browser crypto compatibility is not established. */
class LabPhoneSender(
    private val binding: LabBinding,
    publicKeyset: KeysetHandle,
) {
    private val encryptor: HybridEncrypt
    private var counter = 0

    init {
        HybridConfig.register()
        encryptor = publicKeyset.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
    }

    @Synchronized
    fun encrypt(operation: LabOperation, plaintext: ByteArray): LabCiphertext {
        require(plaintext.isNotEmpty() && plaintext.size <= 4_096)
        check(counter < 128)
        val number = ++counter
        return LabCiphertext(
            operation, number,
            encryptor.encrypt(plaintext, binding.context(operation, number)),
        )
    }
}

enum class LabDecryptDenial { INVALID, REPLAYED, LIMIT_REACHED }

/**
 * Fail-closed receiver with context binding and strictly increasing counters.
 * Do not treat this replay guard as an authorization mechanism.
 */
class LabReceiver internal constructor(
    private val binding: LabBinding,
    val publicKeyset: KeysetHandle,
    private val decryptor: HybridDecrypt,
) {
    private var lastAcceptedCounter = 0

    @Synchronized
    fun decrypt(message: LabCiphertext): LabDecryptResult {
        if (message.counter !in 1..128 || message.bytes.size !in 17..5_120) {
            return LabDecryptResult.Denied(LabDecryptDenial.INVALID)
        }
        if (lastAcceptedCounter >= 128) {
            return LabDecryptResult.Denied(LabDecryptDenial.LIMIT_REACHED)
        }
        if (message.counter <= lastAcceptedCounter) {
            return LabDecryptResult.Denied(LabDecryptDenial.REPLAYED)
        }
        val plain = try {
            decryptor.decrypt(
                message.bytes,
                binding.context(message.operation, message.counter),
            )
        } catch (_: GeneralSecurityException) {
            return LabDecryptResult.Denied(LabDecryptDenial.INVALID)
        }
        if (plain.isEmpty() || plain.size > 4_096) {
            plain.fill(0)
            return LabDecryptResult.Denied(LabDecryptDenial.INVALID)
        }
        lastAcceptedCounter = message.counter
        val accepted = LabDecryptResult.Accepted(plain)
        plain.fill(0)
        return accepted
    }
}

sealed interface LabDecryptResult {
    class Accepted(plaintext: ByteArray) : LabDecryptResult {
        private val secret = plaintext.clone()
        private var consumed = false
        @Synchronized
        fun consumeOnce(block: (ByteArray) -> Unit) {
            check(!consumed) { "HPKE decrypted payload already consumed." }
            consumed = true
            val copy = secret.clone()
            secret.fill(0)
            try { block(copy) } finally { copy.fill(0) }
        }
        override fun toString(): String = "LabDecryptResult.Accepted([REDACTED])"
    }
    class Denied(val reason: LabDecryptDenial) : LabDecryptResult
}

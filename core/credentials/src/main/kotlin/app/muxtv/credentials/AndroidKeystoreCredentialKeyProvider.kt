package app.muxtv.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CredentialKeyUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException(MESSAGE, cause) {
    override fun toString(): String = "${this::class.java.name}: $MESSAGE"

    private companion object {
        const val MESSAGE = "Credential encryption key is unavailable."
    }
}

class AndroidKeystoreCredentialKeyProvider(
    private val alias: String,
) {
    init {
        require(alias.isNotBlank()) { "Credential key alias must not be blank." }
    }

    @Synchronized
    fun getOrCreate(): SecretKey {
        getExistingOrNull()?.let { return it }

        return try {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKey()
        } catch (error: Exception) {
            throw CredentialKeyUnavailableException(error)
        }
    }

    @Synchronized
    fun getExisting(): SecretKey =
        getExistingOrNull() ?: throw CredentialKeyUnavailableException()

    @Synchronized
    fun delete() {
        try {
            keyStore().deleteEntry(alias)
        } catch (error: Exception) {
            throw CredentialKeyUnavailableException(error)
        }
    }

    private fun getExistingOrNull(): SecretKey? {
        return try {
            val store = keyStore()
            if (!store.containsAlias(alias)) return null
            store.getKey(alias, null) as? SecretKey
                ?: throw CredentialKeyUnavailableException()
        } catch (error: CredentialKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw CredentialKeyUnavailableException(error)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256
    }
}

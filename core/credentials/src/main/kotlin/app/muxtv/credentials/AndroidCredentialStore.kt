package app.muxtv.credentials

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

class AndroidKeystoreCredentialCipher(
    private val keyProvider: AndroidKeystoreCredentialKeyProvider,
) : CredentialCipher {
    private val aead = AesGcmCredentialAead(
        encryptionKey = keyProvider::getOrCreate,
        decryptionKey = keyProvider::getExisting,
    )

    override fun encrypt(
        id: CredentialId,
        plaintext: ByteArray,
    ): ByteArray {
        val envelope = aead.encrypt(id, plaintext)
        val iv = envelope.iv()
        val ciphertext = envelope.ciphertext()
        return try {
            CredentialEnvelopeCodec.encode(iv, ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    override fun decrypt(
        id: CredentialId,
        encodedEnvelope: ByteArray,
    ): ByteArray = aead.decrypt(
        id = id,
        envelope = CredentialEnvelopeCodec.decode(encodedEnvelope),
    )

    override fun reset() {
        keyProvider.delete()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "app.muxtv.credentials.v1"
    }
}

class DataStoreCredentialRecordStorage(
    private val dataStore: DataStore<Preferences>,
) : CredentialRecordStorage {
    override suspend fun read(id: CredentialId): ByteArray? {
        val encoded = try {
            dataStore.data.first()[preferenceKey(id)]
        } catch (error: CorruptionException) {
            throw CredentialRecordCorruptionException(error)
        } ?: return null

        return try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw CredentialRecordCorruptionException(error)
        }
    }

    override suspend fun write(
        id: CredentialId,
        encodedEnvelope: ByteArray,
    ) {
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(encodedEnvelope)
        try {
            dataStore.edit { preferences ->
                preferences[preferenceKey(id)] = encoded
            }
        } catch (error: CorruptionException) {
            throw CredentialRecordCorruptionException(error)
        }
    }

    override suspend fun remove(id: CredentialId): Boolean {
        var removed = false
        try {
            dataStore.edit { preferences ->
                removed = preferences.remove(preferenceKey(id)) != null
            }
        } catch (error: CorruptionException) {
            throw CredentialRecordCorruptionException(error)
        }
        return removed
    }

    override suspend fun clear() {
        try {
            dataStore.edit { preferences ->
                preferences.clear()
            }
        } catch (error: CorruptionException) {
            throw CredentialRecordCorruptionException(error)
        }
    }

    private fun preferenceKey(id: CredentialId): Preferences.Key<String> =
        stringPreferencesKey("credential.v1.${id.value}")
}

class AndroidCredentialStoreFactory(
    context: Context,
    private val scope: CoroutineScope,
    private val keyAlias: String = AndroidKeystoreCredentialCipher.DEFAULT_KEY_ALIAS,
) {
    private val applicationContext = context.applicationContext

    @Volatile
    private var instance: CredentialStore? = null

    fun get(): CredentialStore = instance ?: synchronized(this) {
        instance ?: createStore().also { instance = it }
    }

    private fun createStore(): CredentialStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = ::credentialDataStoreFile,
        )
        return DefaultCredentialStore(
            records = DataStoreCredentialRecordStorage(dataStore),
            cipher = AndroidKeystoreCredentialCipher(
                AndroidKeystoreCredentialKeyProvider(keyAlias),
            ),
        )
    }

    private fun credentialDataStoreFile(): File {
        val directory = File(applicationContext.noBackupFilesDir, DIRECTORY_NAME)
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create credential storage directory."
        }
        return File(directory, FILE_NAME)
    }

    private companion object {
        const val DIRECTORY_NAME = "muxtv-credentials"
        const val FILE_NAME = "credentials.preferences_pb"
    }
}

package app.muxtv.credentials

import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialKeyProviderTest {
    private lateinit var provider: AndroidKeystoreCredentialKeyProvider
    private lateinit var alias: String

    @Before
    fun setUp() {
        alias = "app.muxtv.credentials.test.${UUID.randomUUID()}"
        provider = AndroidKeystoreCredentialKeyProvider(alias)
        provider.delete()
    }

    @After
    fun tearDown() {
        provider.delete()
    }

    @Test
    fun generatedKeyIsNonExportableAndCanBeReopened() {
        val generated = provider.getOrCreate()
        val reopened = AndroidKeystoreCredentialKeyProvider(alias).getExisting()

        assertThat(generated.algorithm).isEqualTo("AES")
        assertThat(generated.encoded).isNull()
        assertThat(reopened.algorithm).isEqualTo("AES")
        assertThat(reopened.encoded).isNull()
    }

    @Test
    fun missingKeyIsReportedExplicitly() {
        val error = assertThrows(CredentialKeyUnavailableException::class.java) {
            provider.getExisting()
        }

        assertThat(error.message).isEqualTo("Credential encryption key is unavailable.")
        assertThat(error.toString()).doesNotContain(alias)
    }

    @Test
    fun realKeystoreKeyEncryptsAndDecryptsCredentialEnvelope() {
        val aead = aead(provider)
        val id = CredentialId.random()
        val envelope = aead.encrypt(id, byteArrayOf(1, 2, 3, 4))

        assertThat(aead.decrypt(id, envelope)).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun deletedKeyProducesRecoverableUnavailableState() {
        val aead = aead(provider)
        val id = CredentialId.random()
        val envelope = aead.encrypt(id, byteArrayOf(7, 8, 9))

        provider.delete()

        val error = assertThrows(CredentialKeyUnavailableException::class.java) {
            aead.decrypt(id, envelope)
        }
        assertThat(error.message).isEqualTo("Credential encryption key is unavailable.")
    }

    private fun aead(
        keyProvider: AndroidKeystoreCredentialKeyProvider,
    ): AesGcmCredentialAead = AesGcmCredentialAead(
        encryptionKey = keyProvider::getOrCreate,
        decryptionKey = keyProvider::getExisting,
    )
}

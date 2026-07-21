package app.muxtv.credentials

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CredentialStoreContractTest {
    @Test
    fun `put encrypts and read returns an owned secret`() = runTest {
        val id = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")
        val records = FakeCredentialRecordStorage()
        val cipher = FakeCredentialCipher()
        val store = DefaultCredentialStore(records, cipher)
        val source = byteArrayOf(1, 2, 3, 4)

        val write = SecretBytes.copyOf(source).use { secret ->
            store.put(id, secret)
        }
        source.fill(9)
        val read = store.read(id)

        assertThat(write).isEqualTo(CredentialWriteResult.Stored)
        assertThat(records.values.keys).containsExactly(id)
        assertThat(cipher.encryptedIds).containsExactly(id)
        assertThat(read).isInstanceOf(CredentialReadResult.Found::class.java)
        val found = read as CredentialReadResult.Found
        found.secret.use { secret ->
            assertThat(secret.copyBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun `missing record is distinct from unavailable credential`() = runTest {
        val store = DefaultCredentialStore(FakeCredentialRecordStorage(), FakeCredentialCipher())
        val id = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")

        assertThat(store.read(id)).isEqualTo(CredentialReadResult.NotFound)
    }

    @Test
    fun `authentication failure maps to explicit unavailable result`() = runTest {
        val id = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")
        val records = FakeCredentialRecordStorage().apply {
            values[id] = byteArrayOf(7, 8, 9)
        }
        val cipher = FakeCredentialCipher(
            decryptFailure = CredentialAuthenticationException(IllegalStateException("tampered")),
        )
        val store = DefaultCredentialStore(records, cipher)

        assertThat(store.read(id)).isEqualTo(
            CredentialReadResult.Unavailable(CredentialUnavailableReason.AuthenticationFailed),
        )
    }

    @Test
    fun `missing keystore key maps to reauthentication state`() = runTest {
        val id = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")
        val records = FakeCredentialRecordStorage().apply {
            values[id] = byteArrayOf(7, 8, 9)
        }
        val cipher = FakeCredentialCipher(
            decryptFailure = CredentialKeyUnavailableException(),
        )
        val store = DefaultCredentialStore(records, cipher)

        assertThat(store.read(id)).isEqualTo(
            CredentialReadResult.Unavailable(CredentialUnavailableReason.KeyMissingOrInvalidated),
        )
    }

    @Test
    fun `storage failures are mapped without leaking exception details`() = runTest {
        val id = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")
        val records = FakeCredentialRecordStorage(readFailure = IOException("provider-password=secret"))
        val store = DefaultCredentialStore(records, FakeCredentialCipher())

        val result = store.read(id)

        assertThat(result).isEqualTo(
            CredentialReadResult.Unavailable(CredentialUnavailableReason.IoFailure),
        )
        assertThat(result.toString()).doesNotContain("secret")
    }

    @Test
    fun `remove and reset mutate encrypted records only`() = runTest {
        val first = CredentialId.parse("123e4567-e89b-12d3-a456-426614174000")
        val second = CredentialId.parse("123e4567-e89b-12d3-a456-426614174001")
        val records = FakeCredentialRecordStorage().apply {
            values[first] = byteArrayOf(1)
            values[second] = byteArrayOf(2)
        }
        val cipher = FakeCredentialCipher()
        val store = DefaultCredentialStore(records, cipher)

        assertThat(store.remove(first)).isEqualTo(CredentialRemoveResult.Removed)
        assertThat(records.values.keys).containsExactly(second)
        assertThat(store.reset()).isEqualTo(CredentialResetResult.Reset)
        assertThat(records.values).isEmpty()
        assertThat(cipher.resetCalls).isEqualTo(1)
    }
}

private class FakeCredentialRecordStorage(
    private val readFailure: Throwable? = null,
) : CredentialRecordStorage {
    val values = linkedMapOf<CredentialId, ByteArray>()

    override suspend fun read(id: CredentialId): ByteArray? {
        readFailure?.let { throw it }
        return values[id]?.copyOf()
    }

    override suspend fun write(id: CredentialId, encodedEnvelope: ByteArray) {
        values[id] = encodedEnvelope.copyOf()
    }

    override suspend fun remove(id: CredentialId): Boolean = values.remove(id) != null

    override suspend fun clear() {
        values.clear()
    }
}

private class FakeCredentialCipher(
    private val decryptFailure: Throwable? = null,
) : CredentialCipher {
    val encryptedIds = mutableListOf<CredentialId>()
    var resetCalls: Int = 0

    override fun encrypt(id: CredentialId, plaintext: ByteArray): ByteArray {
        encryptedIds += id
        return plaintext.copyOf()
    }

    override fun decrypt(id: CredentialId, encodedEnvelope: ByteArray): ByteArray {
        decryptFailure?.let { throw it }
        return encodedEnvelope.copyOf()
    }

    override fun reset() {
        resetCalls += 1
    }
}

package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Test

class XtreamSourceAccessTimeZoneContractTest {
    @Test
    fun `encrypted access round trip preserves normalized archive timezone`() = runTest {
        val manager = XtreamSourceAccessManager(TimeZoneCredentialStore())
        val access = XtreamSourceAccess(
            baseUrl = "https://provider.example/",
            username = "user",
            password = "pass",
            archiveTimeZoneId = "Europe/Stockholm",
        )

        assertThat(manager.save(CREDENTIAL_ID, access)).isEqualTo(CredentialWriteResult.Stored)
        val read = manager.read(CREDENTIAL_ID) as XtreamSourceAccessReadResult.Found

        assertThat(read.access.archiveTimeZoneId).isEqualTo("Europe/Stockholm")
        assertThat(read.access.toString()).doesNotContain("Europe/Stockholm")
    }

    @Test
    fun `legacy v1 access decodes with no archive timezone`() = runTest {
        val store = TimeZoneCredentialStore(legacyV1Record())
        val manager = XtreamSourceAccessManager(store)

        val read = manager.read(CREDENTIAL_ID) as XtreamSourceAccessReadResult.Found

        assertThat(read.access.baseUrl).isEqualTo("https://legacy.example/")
        assertThat(read.access.username).isEqualTo("legacy-user")
        assertThat(read.access.password).isEqualTo("legacy-pass")
        assertThat(read.access.insecureHttpApproved).isTrue()
        assertThat(read.access.archiveTimeZoneId).isNull()
    }

    private fun legacyV1Record(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte()))
            data.writeByte(1)
            data.writeBoolean(true)
            data.writeUtf8Field("https://legacy.example/")
            data.writeUtf8Field("legacy-user")
            data.writeUtf8Field("legacy-pass")
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeUtf8Field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId =
            CredentialId.parse("00000000-0000-0000-0000-000000000334")
    }
}

private class TimeZoneCredentialStore(
    initialRecord: ByteArray? = null,
) : CredentialStore {
    private var record: ByteArray? = initialRecord?.copyOf()

    override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
        record = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult =
        record?.let { CredentialReadResult.Found(SecretBytes.copyOf(it)) }
            ?: CredentialReadResult.NotFound

    override suspend fun remove(id: CredentialId): CredentialRemoveResult {
        val existed = record != null
        record = null
        return if (existed) CredentialRemoveResult.Removed else CredentialRemoveResult.NotFound
    }

    override suspend fun reset(): CredentialResetResult {
        record = null
        return CredentialResetResult.Reset
    }
}

package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteEpgRefreshContractsTest {
    @Test
    fun `validators preserve bounded values but redact diagnostics`() {
        val validators = EpgHttpValidators(
            etag = "W/\"private-etag\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
        )

        assertThat(validators.etag).isEqualTo("W/\"private-etag\"")
        assertThat(validators.lastModified).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT")
        assertThat(validators.toString()).doesNotContain("private-etag")
        assertThat(validators.toString()).doesNotContain("21 Oct")
    }

    @Test
    fun `validators reject control separators and excessive values`() {
        assertThrows(IllegalArgumentException::class.java) {
            EpgHttpValidators(etag = "private\r\nInjected: value")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgHttpValidators(etag = "x".repeat(1_025))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EpgHttpValidators(lastModified = "x".repeat(257))
        }
    }

    @Test
    fun `request diagnostics hide source and credential identity`() {
        val request = RemoteEpgRefreshRequest(
            sourceId = "private-source-id",
            sourceName = "Private provider guide",
            providerSourceId = "private-provider-source",
            accessCredentialId = CREDENTIAL_ID,
            defaultZoneId = "Europe/Berlin",
            validators = EpgHttpValidators(etag = "private-etag"),
        )

        val diagnostic = request.toString()

        assertThat(diagnostic).doesNotContain("private-source-id")
        assertThat(diagnostic).doesNotContain("Private provider guide")
        assertThat(diagnostic).doesNotContain("private-provider-source")
        assertThat(diagnostic).doesNotContain(CREDENTIAL_ID.value)
        assertThat(diagnostic).doesNotContain("private-etag")
        assertThat(diagnostic).doesNotContain("Europe/Berlin")
    }

    @Test
    fun `successful result diagnostics hide validator values`() {
        val result = RemoteEpgRefreshResult.Refreshed(
            revisionNumber = 2,
            previousRevisionNumber = 1,
            channelCount = 10,
            programmeCount = 100,
            skippedProgrammeCount = 2,
            warningCount = 3,
            unresolvedTimeCount = 1,
            payloadFormat = EpgPayloadFormat.Gzip,
            validators = EpgHttpValidators(etag = "private-etag"),
        )

        assertThat(result.toString()).doesNotContain("private-etag")
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000069",
        )
    }
}
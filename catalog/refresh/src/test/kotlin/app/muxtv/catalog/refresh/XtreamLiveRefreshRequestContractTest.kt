package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamLiveRefreshRequestContractTest {
    @Test
    fun `Xtream refresh rejects an M3U access reference`() {
        val failure = runCatching {
            XtreamLiveRefreshRequest(
                sourceId = SOURCE_ID,
                sourceName = SOURCE_NAME,
                accessCredentialId = CREDENTIAL_ID,
                accessReference = SourceAccessReference.m3u(CREDENTIAL_ID),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).doesNotContain(CREDENTIAL_ID.value)
    }

    @Test
    fun `Xtream refresh rejects an access reference owned by another credential`() {
        val failure = runCatching {
            XtreamLiveRefreshRequest(
                sourceId = SOURCE_ID,
                sourceName = SOURCE_NAME,
                accessCredentialId = CREDENTIAL_ID,
                accessReference = SourceAccessReference.xtream(OTHER_CREDENTIAL_ID),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).doesNotContain(CREDENTIAL_ID.value)
        assertThat(failure?.message).doesNotContain(OTHER_CREDENTIAL_ID.value)
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000224",
        )
        val OTHER_CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000225",
        )
        const val SOURCE_ID = "source-xtream-live"
        const val SOURCE_NAME = "Synthetic Xtream Live"
    }
}

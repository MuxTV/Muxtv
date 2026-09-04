package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class XtreamPlaybackReferenceResolverContractTest {
    @Test
    fun `explicit m3u8 reference resolves to an ephemeral encoded live URL`() = runTest {
        val store = PlaybackCredentialStore()
        val manager = XtreamSourceAccessManager(store)
        manager.save(
            CREDENTIAL_ID,
            XtreamSourceAccess(
                baseUrl = "https://provider.example/root/",
                username = USERNAME,
                password = PASSWORD,
            ),
        )
        val resolver = XtreamPlaybackReferenceResolver(manager)

        val result = resolver.resolve(
            PlaybackReferenceRequest(
                credentialRef = CREDENTIAL_ID.value,
                playbackReference = "muxtv-provider://xtream/live/707/m3u8",
            ),
        )

        assertThat(result).isInstanceOf(PlaybackReferenceResolution.Ready::class.java)
        val ready = result as PlaybackReferenceResolution.Ready
        assertThat(ready.locator)
            .isEqualTo("https://provider.example/root/live/user%2Fname/p%20ass/707.m3u8")
        assertThat(ready.insecureHttpPreapproved).isFalse()
        assertThat(ready.toString()).doesNotContain(USERNAME)
        assertThat(ready.toString()).doesNotContain(PASSWORD)
        assertThat(ready.toString()).doesNotContain("provider.example")
        assertThat(ready.toString()).doesNotContain("707")
    }

    @Test
    fun `archive reference resolves to conventional UTC timeshift URL without diagnostic leakage`() =
        runTest {
            val manager = manager(
                XtreamSourceAccess(
                    baseUrl = "https://provider.example/root/",
                    username = USERNAME,
                    password = PASSWORD,
                ),
            )

            val result = XtreamPlaybackReferenceResolver(manager).resolve(
                PlaybackReferenceRequest(CREDENTIAL_ID.value, ARCHIVE_REFERENCE),
            )

            assertThat(result).isInstanceOf(PlaybackReferenceResolution.Ready::class.java)
            val ready = result as PlaybackReferenceResolution.Ready
            assertThat(ready.locator).isEqualTo(
                "https://provider.example/root/timeshift/" +
                    "user%2Fname/p%20ass/91/2026-09-02:14-05/707.m3u8",
            )
            assertThat(ready.insecureHttpPreapproved).isFalse()
            assertThat(ready.toString()).doesNotContain(USERNAME)
            assertThat(ready.toString()).doesNotContain(PASSWORD)
            assertThat(ready.toString()).doesNotContain("provider.example")
            assertThat(ready.toString()).doesNotContain("707")
        }

    @Test
    fun `archive reference reuses existing plain http approval gate`() = runTest {
        val manager = manager(
            XtreamSourceAccess(
                baseUrl = "http://provider.example/",
                username = USERNAME,
                password = PASSWORD,
                insecureHttpApproved = false,
            ),
        )

        val result = XtreamPlaybackReferenceResolver(manager).resolve(
            PlaybackReferenceRequest(CREDENTIAL_ID.value, ARCHIVE_REFERENCE),
        )

        assertThat(result).isInstanceOf(PlaybackReferenceResolution.ApprovalRequired::class.java)
        assertThat(result.toString()).doesNotContain(USERNAME)
        assertThat(result.toString()).doesNotContain(PASSWORD)
        assertThat(result.toString()).doesNotContain("707")
    }

    @Test
    fun `malformed archive reference fails closed before credential lookup`() = runTest {
        val resolver = XtreamPlaybackReferenceResolver(manager = manager(null))

        val result = resolver.resolve(
            PlaybackReferenceRequest(
                credentialRef = CREDENTIAL_ID.value,
                playbackReference = "muxtv-provider://xtream/archive/707/0/1788357900000/m3u8",
            ),
        )

        assertThat(result).isEqualTo(PlaybackReferenceResolution.InvalidReference)
    }

    @Test
    fun `legacy format-less reference resolves as ts without changing persisted identity`() = runTest {
        val manager = manager(
            XtreamSourceAccess(
                baseUrl = "https://provider.example/",
                username = "user",
                password = "pass",
            ),
        )
        val reference = "muxtv-provider://xtream/live/707"

        val result = XtreamPlaybackReferenceResolver(manager).resolve(
            PlaybackReferenceRequest(CREDENTIAL_ID.value, reference),
        )

        assertThat(result).isInstanceOf(PlaybackReferenceResolution.Ready::class.java)
        assertThat((result as PlaybackReferenceResolution.Ready).locator)
            .isEqualTo("https://provider.example/live/user/pass/707.ts")
        assertThat(reference).isEqualTo("muxtv-provider://xtream/live/707")
    }

    @Test
    fun `plain http requires provider approval before a credential URL is exposed`() = runTest {
        val manager = manager(
            XtreamSourceAccess(
                baseUrl = "http://provider.example/",
                username = USERNAME,
                password = PASSWORD,
                insecureHttpApproved = false,
            ),
        )

        val result = XtreamPlaybackReferenceResolver(manager).resolve(
            PlaybackReferenceRequest(CREDENTIAL_ID.value, "muxtv-provider://xtream/live/707/ts"),
        )

        assertThat(result).isInstanceOf(PlaybackReferenceResolution.ApprovalRequired::class.java)
        assertThat(result.toString()).doesNotContain(USERNAME)
        assertThat(result.toString()).doesNotContain(PASSWORD)
        assertThat(result.toString()).doesNotContain("707")
    }

    @Test
    fun `approved plain http is marked preapproved for the existing access policy`() = runTest {
        val manager = manager(
            XtreamSourceAccess(
                baseUrl = "http://provider.example/",
                username = "user",
                password = "pass",
                insecureHttpApproved = true,
            ),
        )

        val result = XtreamPlaybackReferenceResolver(manager).resolve(
            PlaybackReferenceRequest(CREDENTIAL_ID.value, "muxtv-provider://xtream/live/707/ts"),
        )

        assertThat(result).isInstanceOf(PlaybackReferenceResolution.Ready::class.java)
        assertThat((result as PlaybackReferenceResolution.Ready).insecureHttpPreapproved).isTrue()
    }

    @Test
    fun `direct locator is unhandled while unknown provider references fail closed`() = runTest {
        val resolver = XtreamPlaybackReferenceResolver(manager = manager(null))

        assertThat(
            resolver.resolve(PlaybackReferenceRequest("", "https://example.test/live.m3u8")),
        ).isEqualTo(PlaybackReferenceResolution.Unhandled)
        assertThat(
            resolver.resolve(PlaybackReferenceRequest("", "muxtv-provider://other/live/707")),
        ).isEqualTo(PlaybackReferenceResolution.InvalidReference)
        assertThat(
            resolver.resolve(PlaybackReferenceRequest("", "muxtv-provider://xtream/live/not-a-number")),
        ).isEqualTo(PlaybackReferenceResolution.InvalidReference)
    }

    @Test
    fun `missing credential is typed and request diagnostics redact controlled values`() = runTest {
        val request = PlaybackReferenceRequest(
            credentialRef = CREDENTIAL_ID.value,
            playbackReference = "muxtv-provider://xtream/live/707/ts",
        )
        val result = XtreamPlaybackReferenceResolver(manager(null)).resolve(request)

        assertThat(result).isEqualTo(PlaybackReferenceResolution.CredentialNotFound)
        assertThat(request.toString()).doesNotContain(CREDENTIAL_ID.value)
        assertThat(request.toString()).doesNotContain("707")
        assertThat(request.toString()).doesNotContain("xtream")
    }

    private suspend fun manager(access: XtreamSourceAccess?): XtreamSourceAccessManager {
        val manager = XtreamSourceAccessManager(PlaybackCredentialStore())
        if (access != null) manager.save(CREDENTIAL_ID, access)
        return manager
    }

    private companion object {
        val CREDENTIAL_ID: CredentialId = CredentialId.parse("00000000-0000-0000-0000-000000000234")
        const val USERNAME = "user/name"
        const val PASSWORD = "p ass"
        const val ARCHIVE_REFERENCE =
            "muxtv-provider://xtream/archive/707/91/1788357900000/m3u8"
    }
}

private class PlaybackCredentialStore : CredentialStore {
    private val records = mutableMapOf<CredentialId, ByteArray>()

    override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
        records[id] = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult =
        records[id]?.let { CredentialReadResult.Found(SecretBytes.copyOf(it)) }
            ?: CredentialReadResult.NotFound

    override suspend fun remove(id: CredentialId): CredentialRemoveResult =
        if (records.remove(id) != null) CredentialRemoveResult.Removed else CredentialRemoveResult.NotFound

    override suspend fun reset(): CredentialResetResult {
        records.clear()
        return CredentialResetResult.Reset
    }
}

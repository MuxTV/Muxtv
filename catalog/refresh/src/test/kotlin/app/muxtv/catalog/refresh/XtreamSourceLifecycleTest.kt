package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class XtreamSourceLifecycleTest {
    @Test
    fun `activation routes opaque Xtream access through Live refresh without cleanup`() = runBlocking {
        val credentialId = CredentialId.parse("00000000-0000-4000-8000-000000000232")
        val accessReference = SourceAccessReference.xtream(credentialId)
        var capturedRequest: XtreamLiveRefreshRequest? = null
        var cleanupCalls = 0
        val lifecycle = XtreamSourceLifecycle(
            accessManager = XtreamSourceAccessManager(UnexpectedCredentialStore),
            activator = XtreamSourceActivator { request ->
                capturedRequest = request
                XtreamLiveRefreshResult.Refreshed(
                    revisionNumber = 2,
                    previousRevisionNumber = 1,
                    entryCount = 3,
                    skippedEntries = 0,
                    warningCount = 0,
                )
            },
            activationCleanup = RemoteSourceActivationCleanup { _, _ ->
                cleanupCalls += 1
                RemoteSourceMetadataCleanupResult.NotFound
            },
        )

        val result = lifecycle.activate(
            accessReference = accessReference,
            sourceName = "  News  ",
        )

        assertThat(result).isInstanceOf(RemoteSourceActivationResult.Activated::class.java)
        val activated = result as RemoteSourceActivationResult.Activated
        assertThat(activated.sourceId).startsWith("source-")
        assertThat(activated.revisionNumber).isEqualTo(2)
        assertThat(capturedRequest).isNotNull()
        assertThat(capturedRequest!!.sourceId).isEqualTo(activated.sourceId)
        assertThat(capturedRequest!!.sourceName).isEqualTo("News")
        assertThat(capturedRequest!!.accessCredentialId).isEqualTo(credentialId)
        assertThat(capturedRequest!!.accessReference).isEqualTo(accessReference)
        assertThat(capturedRequest!!.refreshRunToken).isNull()
        assertThat(cleanupCalls).isEqualTo(0)
    }
}

private object UnexpectedCredentialStore : CredentialStore {
    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult = error("Credential writes are not part of successful activation routing.")

    override suspend fun read(id: CredentialId): CredentialReadResult =
        error("Credential reads belong to XtreamLiveRefresher, not the lifecycle owner.")

    override suspend fun remove(id: CredentialId): CredentialRemoveResult =
        error("Successful activation must retain the Xtream credential.")

    override suspend fun reset(): CredentialResetResult =
        error("Credential reset is outside source activation.")
}

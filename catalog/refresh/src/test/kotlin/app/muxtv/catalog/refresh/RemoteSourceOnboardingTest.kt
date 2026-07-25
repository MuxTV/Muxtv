package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemoteSourceOnboardingTest {
    @Test
    fun prepareStoresNormalizedAccessAndReturnsOnlySanitizedEndpoint() = runTest {
        val credentials = InMemoryCredentialStore()
        val onboarding = onboarding(credentials) {
            error("Activation must not run during preparation.")
        }

        val result = onboarding.prepare(
            RemoteSourceOnboardingInput(
                locator = "  https://Example.com/live/list.m3u?token=secret  ",
                userAgent = "Provider Agent",
                referrer = "https://portal.example/private",
                sensitiveHeaders = mapOf("Authorization" to "Bearer secret"),
            ),
        )

        val prepared = result as RemoteSourcePreparationResult.Prepared
        assertThat(prepared.scheme).isEqualTo("https")
        assertThat(prepared.host).isEqualTo("example.com")
        assertThat(prepared.toString()).doesNotContain("secret")
        assertThat(prepared.token.toString()).isEqualTo("<redacted>")

        val stored = credentials.read(prepared.token.credentialId()) as CredentialReadResult.Found
        val access = stored.secret.use { secret -> RemoteSourceAccessCodec.decode(secret) }
        assertThat(access.url).isEqualTo("https://example.com/live/list.m3u?token=secret")
        assertThat(access.userAgent).isEqualTo("Provider Agent")
        assertThat(access.referrer).isEqualTo("https://portal.example/private")
        assertThat(access.sensitiveHeaders).containsExactly("Authorization", "Bearer secret")
    }

    @Test
    fun prepareRejectsHttpWithoutExplicitApprovalBeforeStorage() = runTest {
        val credentials = InMemoryCredentialStore()
        val onboarding = onboarding(credentials) {
            error("Activation must not run during preparation.")
        }

        val result = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "http://provider.example/list.m3u"),
        )

        assertThat(result).isEqualTo(RemoteSourcePreparationResult.InsecureTransportApprovalRequired)
        assertThat(credentials.recordCount).isEqualTo(0)
    }

    @Test
    fun prepareRejectsEmbeddedCredentialsAndFragmentsBeforeStorage() = runTest {
        val credentials = InMemoryCredentialStore()
        val onboarding = onboarding(credentials) {
            error("Activation must not run during preparation.")
        }

        val embedded = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://user:password@example.com/list.m3u"),
        )
        val fragment = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u#token"),
        )

        assertThat((embedded as RemoteSourcePreparationResult.UrlRejected).reason.name)
            .isEqualTo("EmbeddedCredentials")
        assertThat((fragment as RemoteSourcePreparationResult.UrlRejected).reason.name)
            .isEqualTo("Fragment")
        assertThat(credentials.recordCount).isEqualTo(0)
    }

    @Test
    fun successfulActivationUsesDeterministicOpaqueSourceIdentityAndRetainsCredential() = runTest {
        val credentials = InMemoryCredentialStore()
        var captured: RemoteSourceRefreshRequest? = null
        var cleanupCalled = false
        val onboarding = onboarding(
            credentials = credentials,
            cleanup = { _, _ ->
                cleanupCalled = true
                RemoteSourceMetadataCleanupResult.NotFound
            },
        ) { request ->
            captured = request
            RemoteSourceRefreshResult.Refreshed(
                revisionNumber = 2,
                previousRevisionNumber = 1,
                entryCount = 120,
                skippedEntries = 3,
                warningCount = 4,
            )
        }
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val result = onboarding.activate(prepared.token, "Primary IPTV")

        val activated = result as RemoteSourceActivationResult.Activated
        assertThat(activated.sourceId).startsWith("source-")
        assertThat(activated.sourceId).doesNotContain(prepared.token.value)
        assertThat(activated.sourceId.length).isEqualTo("source-".length + 64)
        assertThat(activated.revisionNumber).isEqualTo(2)
        assertThat(activated.entryCount).isEqualTo(120)
        assertThat(captured?.sourceId).isEqualTo(activated.sourceId)
        assertThat(captured?.sourceName).isEqualTo("Primary IPTV")
        assertThat(captured?.accessCredentialId).isEqualTo(prepared.token.credentialId())
        assertThat(cleanupCalled).isFalse()
        assertThat(credentials.read(prepared.token.credentialId()))
            .isInstanceOf(CredentialReadResult.Found::class.java)
    }

    @Test
    fun failedActivationRemovesSourceMetadataThenTemporaryCredential() = runTest {
        val credentials = InMemoryCredentialStore()
        var activationSourceId: String? = null
        var cleanedSourceId: String? = null
        var cleanedCredentialRef: String? = null
        val onboarding = onboarding(
            credentials = credentials,
            cleanup = { sourceId, credentialRef ->
                cleanedSourceId = sourceId
                cleanedCredentialRef = credentialRef
                RemoteSourceMetadataCleanupResult.Removed
            },
        ) { request ->
            activationSourceId = request.sourceId
            RemoteSourceRefreshResult.NetworkFailure(RemoteSourceNetworkFailureReason.Timeout)
        }
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val result = onboarding.activate(prepared.token, "Primary IPTV")

        val failed = result as RemoteSourceActivationResult.Failed
        assertThat(failed.failure)
            .isEqualTo(RemoteSourceActivationFailure.Network(RemoteSourceNetworkFailureReason.Timeout))
        assertThat(failed.credentialCleanupFailure).isNull()
        assertThat(failed.sourceCleanupFailure).isNull()
        assertThat(cleanedSourceId).isEqualTo(activationSourceId)
        assertThat(cleanedSourceId).doesNotContain(prepared.token.value)
        assertThat(cleanedCredentialRef).isEqualTo(prepared.token.value)
        assertThat(credentials.read(prepared.token.credentialId()))
            .isEqualTo(CredentialReadResult.NotFound)
    }

    @Test
    fun retainedMetadataKeepsCredentialReferenceValid() = runTest {
        val credentials = InMemoryCredentialStore()
        val onboarding = onboarding(
            credentials = credentials,
            cleanup = { _, _ -> RemoteSourceMetadataCleanupResult.Retained },
        ) {
            RemoteSourceRefreshResult.ImportFailed(
                app.muxtv.catalog.importer.CatalogImportFailureReason.StorageFailure,
            )
        }
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val result = onboarding.activate(prepared.token, "Primary IPTV")

        val failed = result as RemoteSourceActivationResult.Failed
        assertThat(failed.sourceCleanupFailure)
            .isEqualTo(RemoteSourceMetadataCleanupFailure.MetadataRetained)
        assertThat(failed.credentialCleanupFailure).isNull()
        assertThat(credentials.read(prepared.token.credentialId()))
            .isInstanceOf(CredentialReadResult.Found::class.java)
    }

    @Test
    fun unexpectedActivationExceptionRemovesCredentialWithoutExposingExceptionText() = runTest {
        val credentials = InMemoryCredentialStore()
        val onboarding = onboarding(credentials) {
            throw IOException("https://example.com/list.m3u?token=secret")
        }
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val result = onboarding.activate(prepared.token, "Primary IPTV")

        val failed = result as RemoteSourceActivationResult.Failed
        assertThat(failed.failure).isEqualTo(RemoteSourceActivationFailure.Unexpected)
        assertThat(failed.toString()).doesNotContain("secret")
        assertThat(credentials.read(prepared.token.credentialId()))
            .isEqualTo(CredentialReadResult.NotFound)
    }

    @Test
    fun cancellationCleansTemporaryCredentialAndPropagates() = runTest {
        val credentials = InMemoryCredentialStore()
        val cancellation = CancellationException("cancelled")
        val onboarding = onboarding(credentials) { throw cancellation }
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val actual = try {
            onboarding.activate(prepared.token, "Primary IPTV")
            error("CancellationException must be rethrown.")
        } catch (error: CancellationException) {
            error
        }

        assertThat(actual).isSameInstanceAs(cancellation)
        assertThat(credentials.read(prepared.token.credentialId()))
            .isEqualTo(CredentialReadResult.NotFound)
    }

    @Test
    fun cancelRemovesPreparedCredential() = runTest {
        val credentials = InMemoryCredentialStore()
        val onboarding = onboarding(credentials) {
            error("Activation must not run during cancellation.")
        }
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(locator = "https://example.com/list.m3u"),
        ) as RemoteSourcePreparationResult.Prepared

        val result = onboarding.cancel(prepared.token)

        assertThat(result).isEqualTo(RemoteSourceCancellationResult.Removed)
        assertThat(credentials.read(prepared.token.credentialId()))
            .isEqualTo(CredentialReadResult.NotFound)
    }

    private fun onboarding(
        credentials: CredentialStore,
        cleanup: suspend (String, String) -> RemoteSourceMetadataCleanupResult = { _, _ ->
            RemoteSourceMetadataCleanupResult.NotFound
        },
        activate: suspend (RemoteSourceRefreshRequest) -> RemoteSourceRefreshResult,
    ): RemoteSourceOnboarding = DefaultRemoteSourceOnboarding(
        accessManager = RemoteSourceAccessManager(credentials),
        activator = RemoteSourceActivator(activate),
        activationCleanup = RemoteSourceActivationCleanup(cleanup),
    )
}

private class InMemoryCredentialStore : CredentialStore {
    private val records = linkedMapOf<CredentialId, ByteArray>()

    val recordCount: Int
        get() = records.size

    override suspend fun put(
        id: CredentialId,
        secret: SecretBytes,
    ): CredentialWriteResult {
        records.remove(id)?.fill(0)
        records[id] = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult {
        val bytes = records[id] ?: return CredentialReadResult.NotFound
        return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
    }

    override suspend fun remove(id: CredentialId): CredentialRemoveResult {
        val removed = records.remove(id) ?: return CredentialRemoveResult.NotFound
        removed.fill(0)
        return CredentialRemoveResult.Removed
    }

    override suspend fun reset(): CredentialResetResult {
        records.values.forEach { it.fill(0) }
        records.clear()
        return CredentialResetResult.Reset
    }
}

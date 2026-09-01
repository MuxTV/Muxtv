package app.muxtv.catalog.refresh

import app.muxtv.catalog.importer.CatalogRevisionImporter
import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.catalog.ingest.StreamingXtreamParser
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.InactiveSourceRemovalResult
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.SourceRevisionStore
import app.muxtv.database.StagedCatalogEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

class LocalNetworkRefreshGateContractTest {
    @Test
    fun `stored M3U local target is blocked before the first HTTP call`() = runTest {
        val credentials = LocalGateCredentialStore()
        val accessManager = RemoteSourceAccessManager(credentials)
        assertThat(
            accessManager.save(
                M3U_CREDENTIAL_ID,
                RemoteSourceAccess(
                    url = LOCAL_M3U_URL,
                    insecureHttpApproved = true,
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)
        var networkAttempted = false
        val gateTargets = mutableListOf<String>()
        val client = failOnNetworkClient { networkAttempted = true }

        val result = RemoteSourceRefresher(
            accessManager = accessManager,
            importer = noImportImporter(),
            sourceClient = client,
            localNetworkAccessRequired = { normalizedUrl ->
                gateTargets += normalizedUrl
                true
            },
        ).refresh(
            RemoteSourceRefreshRequest(
                sourceId = "source-local-m3u",
                sourceName = "Local M3U",
                accessCredentialId = M3U_CREDENTIAL_ID,
                refreshRunToken = "refresh-local-m3u",
            ),
        )

        assertThat(result).isEqualTo(RemoteSourceRefreshResult.LocalNetworkAccessRequired)
        assertThat(gateTargets).containsExactly(LOCAL_M3U_URL)
        assertThat(networkAttempted).isFalse()
    }

    @Test
    fun `stored Xtream local target is blocked before authentication HTTP call`() = runTest {
        val credentials = LocalGateCredentialStore()
        val accessManager = XtreamSourceAccessManager(credentials)
        assertThat(
            accessManager.save(
                XTREAM_CREDENTIAL_ID,
                XtreamSourceAccess(
                    baseUrl = LOCAL_XTREAM_URL,
                    username = "alice",
                    password = "secret",
                    insecureHttpApproved = true,
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)
        var networkAttempted = false
        val gateTargets = mutableListOf<String>()
        val client = failOnNetworkClient { networkAttempted = true }

        val result = XtreamLiveRefresher(
            accessManager = accessManager,
            importer = noImportImporter(),
            sourceClient = client,
            parser = StreamingXtreamParser(),
            localNetworkAccessRequired = { normalizedUrl ->
                gateTargets += normalizedUrl
                true
            },
        ).refresh(
            XtreamLiveRefreshRequest(
                sourceId = "source-local-xtream",
                sourceName = "Local Xtream",
                accessCredentialId = XTREAM_CREDENTIAL_ID,
                refreshRunToken = "refresh-local-xtream",
            ),
        )

        assertThat(result).isEqualTo(XtreamLiveRefreshResult.LocalNetworkAccessRequired)
        assertThat(gateTargets).containsExactly(NORMALIZED_LOCAL_XTREAM_URL)
        assertThat(networkAttempted).isFalse()
    }

    @Test
    fun `M3U activation LAN barrier preserves prepared credential for grant retry`() = runTest {
        val credentials = LocalGateCredentialStore()
        val accessManager = RemoteSourceAccessManager(credentials)
        var cleanupCalls = 0
        val onboarding = DefaultRemoteSourceOnboarding(
            accessManager = accessManager,
            activator = RemoteSourceActivator { RemoteSourceRefreshResult.LocalNetworkAccessRequired },
            activationCleanup = RemoteSourceActivationCleanup { _, _ ->
                cleanupCalls += 1
                RemoteSourceMetadataCleanupResult.Removed
            },
        )
        val prepared = onboarding.prepare(
            RemoteSourceOnboardingInput(
                locator = LOCAL_M3U_URL,
                insecureHttpApproved = true,
            ),
        ) as RemoteSourcePreparationResult.Prepared

        val result = onboarding.activate(prepared.token, "Local M3U")

        assertThat(result).isEqualTo(RemoteSourceActivationResult.LocalNetworkAccessRequired)
        assertThat(cleanupCalls).isEqualTo(0)
        assertThat(accessManager.read(prepared.token.credentialId()))
            .isInstanceOf(RemoteSourceAccessReadResult.Found::class.java)
    }

    @Test
    fun `Xtream activation LAN barrier preserves prepared credential for grant retry`() = runTest {
        val credentials = LocalGateCredentialStore()
        val accessManager = XtreamSourceAccessManager(credentials)
        assertThat(
            accessManager.save(
                XTREAM_CREDENTIAL_ID,
                XtreamSourceAccess(
                    baseUrl = LOCAL_XTREAM_URL,
                    username = "alice",
                    password = "secret",
                    insecureHttpApproved = true,
                ),
            ),
        ).isEqualTo(CredentialWriteResult.Stored)
        val reference = SourceAccessReference.xtream(XTREAM_CREDENTIAL_ID)
        var cleanupCalls = 0
        val lifecycle = XtreamSourceLifecycle(
            accessManager = accessManager,
            activator = XtreamSourceActivator { XtreamLiveRefreshResult.LocalNetworkAccessRequired },
            activationCleanup = RemoteSourceActivationCleanup { _, _ ->
                cleanupCalls += 1
                RemoteSourceMetadataCleanupResult.Removed
            },
        )

        val result = lifecycle.activate(reference, "Local Xtream")

        assertThat(result).isEqualTo(RemoteSourceActivationResult.LocalNetworkAccessRequired)
        assertThat(cleanupCalls).isEqualTo(0)
        assertThat(accessManager.read(XTREAM_CREDENTIAL_ID))
            .isInstanceOf(XtreamSourceAccessReadResult.Found::class.java)
    }

    private fun failOnNetworkClient(onAttempt: () -> Unit): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { _ ->
                onAttempt()
                error("HTTP must not start before local-network permission is granted")
            }
            .build()

    private fun noImportImporter(): CatalogRevisionImporter = CatalogRevisionImporter(
        parser = StreamingM3uParser(),
        revisionStore = NoImportRevisionStore,
        nowEpochMillis = { 1L },
    )

    private companion object {
        val M3U_CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000252",
        )
        val XTREAM_CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000253",
        )
        const val LOCAL_M3U_URL = "http://192.168.1.20:8080/list.m3u"
        const val LOCAL_XTREAM_URL = "http://192.168.1.21:8080"
        const val NORMALIZED_LOCAL_XTREAM_URL = "$LOCAL_XTREAM_URL/"
    }
}

private class LocalGateCredentialStore : CredentialStore {
    private val records = mutableMapOf<CredentialId, ByteArray>()

    override suspend fun put(id: CredentialId, secret: SecretBytes): CredentialWriteResult {
        records[id] = secret.copyBytes()
        return CredentialWriteResult.Stored
    }

    override suspend fun read(id: CredentialId): CredentialReadResult {
        val bytes = records[id] ?: return CredentialReadResult.NotFound
        return CredentialReadResult.Found(SecretBytes.copyOf(bytes))
    }

    override suspend fun remove(id: CredentialId): CredentialRemoveResult =
        if (records.remove(id) != null) CredentialRemoveResult.Removed else CredentialRemoveResult.NotFound

    override suspend fun reset(): CredentialResetResult {
        records.clear()
        return CredentialResetResult.Reset
    }
}

private object NoImportRevisionStore : SourceRevisionStore {
    override suspend fun upsertSource(source: SourceDefinition) = unexpectedImport()

    override suspend fun nextRevisionNumber(sourceId: String): Long = unexpectedImport()

    override suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) = unexpectedImport()

    override suspend fun stageBatch(
        sourceId: String,
        revisionNumber: Long,
        entries: List<StagedCatalogEntry>,
    ) = unexpectedImport()

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = unexpectedImport()

    override suspend fun activateIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = unexpectedImport()

    override suspend fun activateIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = unexpectedImport()

    override suspend fun discard(sourceId: String, revisionNumber: Long) = unexpectedImport()

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult = unexpectedImport()

    private fun unexpectedImport(): Nothing =
        error("Import/storage must not start before local-network permission is granted")
}

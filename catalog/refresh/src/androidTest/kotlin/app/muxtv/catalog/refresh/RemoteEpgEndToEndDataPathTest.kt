package app.muxtv.catalog.refresh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.importer.CatalogImportRequest
import app.muxtv.catalog.importer.CatalogImportResult
import app.muxtv.catalog.importer.CatalogRevisionImporterFactory
import app.muxtv.catalog.importer.EpgImportFailureReason
import app.muxtv.catalog.importer.EpgRevisionImporterFactory
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.credentials.CredentialResetResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import app.muxtv.database.DatabaseDefaults
import app.muxtv.database.EpgMatchingReconcileResult
import app.muxtv.database.EpgSourceDefinition
import app.muxtv.database.MuxTvDatabaseFactory
import app.muxtv.network.MuxTvHttpClients
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteEpgEndToEndDataPathTest {
    @Test
    fun remoteRefreshPublishesNowNextAndMalformedRefreshPreservesPreviousGood() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DATABASE_NAME)
        val components = MuxTvDatabaseFactory.create(context)
        components.initializer.initialize()

        val catalogImporter = CatalogRevisionImporterFactory.create(components.sourceRevisionStore)
        val catalogResult = catalogImporter.import(
            request = CatalogImportRequest(
                sourceId = PROVIDER_SOURCE,
                sourceName = "Provider",
            ),
            input = ByteArrayInputStream(M3U.toByteArray()),
        )
        assertThat(catalogResult).isInstanceOf(CatalogImportResult.Imported::class.java)

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .headers(headersOf("Content-Type", "application/xml"))
                    .body(XMLTV)
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .headers(headersOf("Content-Type", "application/xml"))
                    .body(MALFORMED_XMLTV)
                    .build(),
            )

            val credentialStore = IntegrationCredentialStore()
            val accessManager = RemoteSourceAccessManager(credentialStore)
            assertThat(
                accessManager.save(
                    CREDENTIAL_ID,
                    RemoteSourceAccess(
                        url = server.url("/guide.xml").toString(),
                        insecureHttpApproved = true,
                    ),
                ),
            ).isEqualTo(CredentialWriteResult.Stored)

            components.epgRevisionStore.upsertSource(
                EpgSourceDefinition(
                    id = EPG_SOURCE,
                    name = "Guide",
                    providerSourceId = PROVIDER_SOURCE,
                    accessRef = CREDENTIAL_ID.value,
                    defaultZoneId = "UTC",
                ),
            )

            val refresher = RemoteEpgRefresher(
                accessManager = accessManager,
                importer = EpgRevisionImporterFactory.create(components.epgRevisionStore),
                sourceClient = MuxTvHttpClients().source,
            )
            val request = RemoteEpgRefreshRequest(
                sourceId = EPG_SOURCE,
                sourceName = "Guide",
                providerSourceId = PROVIDER_SOURCE,
                accessCredentialId = CREDENTIAL_ID,
                defaultZoneId = "UTC",
            )

            val firstRefresh = refresher.refresh(request)
            assertThat(firstRefresh).isInstanceOf(RemoteEpgRefreshResult.Refreshed::class.java)

            val matching = components.epgMatchingStore.reconcile(EPG_SOURCE)
            assertThat(matching).isInstanceOf(EpgMatchingReconcileResult.Applied::class.java)
            val summary = (matching as EpgMatchingReconcileResult.Applied).summary
            assertThat(summary.matchedCount).isEqualTo(1)
            assertThat(summary.ambiguousCount).isEqualTo(0)
            assertThat(summary.unresolvedCount).isEqualTo(0)

            val channel = components.playbackCatalog.observeChannels(
                ChannelQuery(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    limit = 10,
                ),
            ).first().single()
            val now = Instant.parse("2026-08-02T00:30:00Z").toEpochMilli()
            val nextBoundary = Instant.parse("2026-08-02T01:00:00Z").toEpochMilli()

            val beforeFailure = components.epgGuideRepository.getNowNext(
                NowNextQuery(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    canonicalChannelIds = listOf(channel.channelId),
                    nowEpochMillis = now,
                ),
            ).single()
            assertThat(beforeFailure.state).isEqualTo(GuideProjectionState.READY)
            assertThat(beforeFailure.current?.title).isEqualTo("Current News")
            assertThat(beforeFailure.next?.title).isEqualTo("Next News")
            assertThat(beforeFailure.nextBoundaryEpochMillis).isEqualTo(nextBoundary)

            val failedRefresh = refresher.refresh(request)
            assertThat(failedRefresh).isEqualTo(
                RemoteEpgRefreshResult.ImportFailed(EpgImportFailureReason.ParserFailure),
            )

            val afterFailure = components.epgGuideRepository.getNowNext(
                NowNextQuery(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    canonicalChannelIds = listOf(channel.channelId),
                    nowEpochMillis = now,
                ),
            ).single()
            assertThat(afterFailure).isEqualTo(beforeFailure)
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    private companion object {
        const val DATABASE_NAME = "muxtv.db"
        const val PROVIDER_SOURCE = "provider-source"
        const val EPG_SOURCE = "epg-source"

        val CREDENTIAL_ID: CredentialId = CredentialId.parse(
            "00000000-0000-0000-0000-000000000028",
        )

        val M3U = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.id" tvg-name="News",News
            https://example.invalid/news.m3u8
        """.trimIndent()

        val XMLTV = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="news.id">
                <display-name>News</display-name>
              </channel>
              <programme start="20260802000000 +0000" stop="20260802010000 +0000" channel="news.id">
                <title>Current News</title>
              </programme>
              <programme start="20260802010000 +0000" stop="20260802020000 +0000" channel="news.id">
                <title>Next News</title>
              </programme>
            </tv>
        """.trimIndent()

        val MALFORMED_XMLTV = """
            <tv>
              <channel id="news.id"><display-name>Broken</display-name></channel>
              <programme start="20260802030000 +0000" channel="news.id">
                <title>Must never replace previous-good data</title>
            </tv>
        """.trimIndent()
    }
}

private class IntegrationCredentialStore : CredentialStore {
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
        if (records.remove(id) != null) {
            CredentialRemoveResult.Removed
        } else {
            CredentialRemoveResult.NotFound
        }

    override suspend fun reset(): CredentialResetResult {
        records.clear()
        return CredentialResetResult.Reset
    }
}

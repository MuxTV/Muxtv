package app.muxtv.catalog.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.database.DatabaseDefaults
import app.muxtv.database.EpgMatchingReconcileResult
import app.muxtv.database.MuxTvDatabaseFactory
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgEndToEndDataPathTest {
    @Test
    fun xmltvImportMatchesActiveCatalogAndProjectsNowNext() = runBlocking {
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

        val epgImporter = EpgRevisionImporterFactory.create(components.epgRevisionStore)
        val epgResult = epgImporter.import(
            request = EpgImportRequest(
                sourceId = EPG_SOURCE,
                sourceName = "Guide",
                providerSourceId = PROVIDER_SOURCE,
                accessRef = null,
                defaultZoneId = "UTC",
            ),
            input = ByteArrayInputStream(XMLTV.toByteArray()),
        )
        assertThat(epgResult).isInstanceOf(EpgImportResult.Imported::class.java)

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
        val projection = components.epgGuideRepository.getNowNext(
            NowNextQuery(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelIds = listOf(channel.channelId),
                nowEpochMillis = now,
            ),
        ).single()

        assertThat(projection.state).isEqualTo(GuideProjectionState.READY)
        assertThat(projection.current?.title).isEqualTo("Current News")
        assertThat(projection.next?.title).isEqualTo("Next News")
        assertThat(projection.nextBoundaryEpochMillis).isEqualTo(nextBoundary)
    }

    private companion object {
        const val DATABASE_NAME = "muxtv.db"
        const val PROVIDER_SOURCE = "provider-source"
        const val EPG_SOURCE = "epg-source"

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
    }
}

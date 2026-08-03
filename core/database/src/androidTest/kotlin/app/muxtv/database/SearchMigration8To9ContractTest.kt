package app.muxtv.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchMigration8To9ContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val databaseFile = targetContext.getDatabasePath(DATABASE_NAME)

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = databaseFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = MuxTvDatabase::class,
    )

    @Before
    fun setUp() {
        targetContext.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        targetContext.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun v8BackfillBuildsUnicodeSearchIndex() = runBlocking {
        val version8 = migrationHelper.createDatabase(8)
        version8.execSQL("PRAGMA foreign_keys = OFF")
        version8.execSQL(
            "INSERT INTO canonical_channels(id, displayName) VALUES('channel-a', 'РОССИЯ 1')",
        )
        version8.execSQL(
            "INSERT INTO profiles(id, name, isPrimary, archivedAtEpochMillis) " +
                "VALUES('profile-main', 'Основной', 1, NULL)",
        )
        version8.execSQL(
            """
            INSERT INTO user_channel_overlays(
                profileId, canonicalChannelId, isFavorite, customName, channelNumber, isHidden
            ) VALUES('profile-main', 'channel-a', 0, 'Первый русский', 1, 0)
            """.trimIndent(),
        )
        version8.execSQL(
            "INSERT INTO sources(id, name, credentialRef, activeRevision) " +
                "VALUES('source-a', 'Источник', NULL, 1)",
        )
        version8.execSQL(
            """
            INSERT INTO provider_channels(
                id, sourceId, revisionNumber, providerKey, rawName, tvgId, tvgName,
                logoUrl, groupTitle, channelNumber, catchupMode, catchupSource,
                catchupDays, catchupCorrection
            ) VALUES(
                'provider-a', 'source-a', 1, 'provider-key', 'Россия Первый', NULL, NULL,
                NULL, 'Новости', '001', NULL, NULL, NULL, NULL
            )
            """.trimIndent(),
        )
        version8.execSQL(
            """
            INSERT INTO stream_variants(
                id, providerChannelId, canonicalChannelId, locator, userAgent, referrer
            ) VALUES('variant-a', 'provider-a', 'channel-a', 'https://example.invalid/live', NULL, NULL)
            """.trimIndent(),
        )
        version8.execSQL(
            """
            INSERT INTO epg_programmes(
                sourceId, revisionNumber, sequenceNumber, externalChannelId,
                startEpochMillis, stopEpochMillis, primaryTitle, primaryLanguage,
                subtitle, description, category, iconRef, episodeNumber, isNew
            ) VALUES(
                'epg-a', 1, 1, 'epg-channel-a', 1000, 2000, 'ВЕСТИ', 'ru',
                NULL, NULL, NULL, NULL, NULL, 0
            )
            """.trimIndent(),
        )
        version8.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 9,
            migrations = listOf(MIGRATION_8_9),
        )

        migrated.prepare(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'search_documents_fts'",
        ).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getText(0).lowercase()).contains("tokenize=unicode61")
        }

        migrated.prepare("SELECT COUNT(*) FROM search_documents").use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isAtLeast(7L)
        }

        migrated.prepare(
            """
            SELECT d.text
            FROM search_documents_fts
            JOIN search_documents AS d ON d.rowId = search_documents_fts.rowid
            WHERE search_documents_fts MATCH 'рос*'
            ORDER BY d.rowId
            """.trimIndent(),
        ).use { statement ->
            val matches = buildList {
                while (statement.step()) add(statement.getText(0))
            }
            assertThat(matches).containsAtLeast("РОССИЯ 1", "Россия Первый")
        }

        migrated.prepare(
            """
            SELECT d.text
            FROM search_documents_fts
            JOIN search_documents AS d ON d.rowId = search_documents_fts.rowid
            WHERE search_documents_fts MATCH 'вес*'
            """.trimIndent(),
        ).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getText(0)).isEqualTo("ВЕСТИ")
        }

        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "search-migration-8-9-contract.db"
    }
}

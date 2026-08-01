package app.muxtv.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
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
class EpgMatchingMigration6To7Test {
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
    fun v6ToV7AddsRevisionPairMatchingWithoutChangingExistingData() = runBlocking {
        val version6 = migrationHelper.createDatabase(6)
        version6.execSQL("PRAGMA foreign_keys = ON")
        insertVersion6ProducerFixture(version6)
        version6.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 7,
            migrations = listOf(MIGRATION_6_7),
        )
        migrated.execSQL("PRAGMA foreign_keys = ON")

        assertSingleLong(migrated, "SELECT activeRevision FROM sources WHERE id = 'source-1'", 1)
        assertSingleLong(migrated, "SELECT activeRevision FROM epg_sources WHERE id = 'epg-1'", 1)
        assertSchemaObjectExists(migrated, "table", "epg_channel_matches")
        assertSchemaObjectExists(
            migrated,
            "index",
            "index_epg_channel_matches_epgSourceId_epgRevisionNumber_epgExternalChannelId",
        )
        assertSchemaObjectExists(
            migrated,
            "index",
            "index_epg_channel_matches_providerSourceId_catalogRevisionNumber",
        )
        assertSchemaObjectExists(
            migrated,
            "index",
            "index_epg_channel_matches_canonicalChannelId",
        )

        migrated.execSQL(
            """
            INSERT INTO epg_channel_matches(
                epgSourceId,
                epgRevisionNumber,
                providerSourceId,
                catalogRevisionNumber,
                epgExternalChannelId,
                decision,
                reasonCode,
                canonicalChannelId,
                candidateCount
            ) VALUES(
                'epg-1', 1, 'source-1', 1, 'epg-channel-1',
                'MATCHED', 'EXACT_ID', 'canonical-1', 1
            )
            """.trimIndent(),
        )
        assertSingleLong(migrated, "SELECT COUNT(*) FROM epg_channel_matches", 1)

        migrated.execSQL(
            "DELETE FROM epg_channels " +
                "WHERE sourceId = 'epg-1' AND revisionNumber = 1 AND externalId = 'epg-channel-1'",
        )
        assertSingleLong(migrated, "SELECT COUNT(*) FROM epg_channel_matches", 0)
        migrated.close()
    }

    private fun insertVersion6ProducerFixture(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT INTO sources(id, name, credentialRef, activeRevision) " +
                "VALUES('source-1', 'Playlist', NULL, 1)",
        )
        connection.execSQL(
            """
            INSERT INTO source_revisions(
                sourceId, revisionNumber, status, startedAtEpochMillis, activatedAtEpochMillis,
                parsedEntries, skippedEntries, warningCount
            ) VALUES('source-1', 1, 'ACTIVE', 10, 20, 1, 0, 0)
            """.trimIndent(),
        )
        connection.execSQL(
            "INSERT INTO canonical_channels(id, displayName) VALUES('canonical-1', 'Channel One')",
        )
        connection.execSQL(
            """
            INSERT INTO epg_sources(
                id, name, providerSourceId, accessRef, defaultZoneId, activeRevision
            ) VALUES('epg-1', 'Guide', 'source-1', NULL, 'UTC', 1)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO epg_revisions(
                sourceId, revisionNumber, status, startedAtEpochMillis, activatedAtEpochMillis,
                acceptedChannels, acceptedProgrammes, skippedProgrammes, warningCount,
                unresolvedTimeCount
            ) VALUES('epg-1', 1, 'ACTIVE', 10, 20, 1, 0, 0, 0, 0)
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO epg_channels(
                sourceId, revisionNumber, externalId, primaryDisplayName, primaryLanguage, iconRef
            ) VALUES('epg-1', 1, 'epg-channel-1', 'Channel One', 'en', NULL)
            """.trimIndent(),
        )
    }

    private fun assertSingleLong(connection: SQLiteConnection, query: String, expected: Long) {
        connection.prepare(query).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(expected)
            assertThat(statement.step()).isFalse()
        }
    }

    private fun assertSchemaObjectExists(connection: SQLiteConnection, type: String, name: String) {
        connection.prepare(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
        ).use { statement ->
            statement.bindText(1, type)
            statement.bindText(2, name)
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(1)
            assertThat(statement.step()).isFalse()
        }
    }

    private companion object {
        const val DATABASE_NAME = "epg-matching-migration-6-7.db"
    }
}

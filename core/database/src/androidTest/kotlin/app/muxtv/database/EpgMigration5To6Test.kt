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
class EpgMigration5To6Test {
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
    fun committedV4FixtureAppliesV5AndV6MigrationsAndAddsRefreshPersistence() = runBlocking {
        val version4 = migrationHelper.createDatabase(4)
        version4.execSQL("PRAGMA foreign_keys = ON")
        version4.execSQL(
            "INSERT INTO profiles(id, name, isPrimary, archivedAtEpochMillis) " +
                "VALUES('profile-1', 'Primary', 1, NULL)",
        )
        version4.execSQL(
            "INSERT INTO installations(id, primaryProfileId) VALUES('installation-1', 'profile-1')",
        )
        version4.execSQL(
            "INSERT INTO sources(id, name, credentialRef, activeRevision) " +
                "VALUES('source-1', 'Playlist', 'credential-ref', 1)",
        )
        version4.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 6,
            migrations = listOf(MIGRATION_4_5, MIGRATION_5_6),
        )

        assertSingleLong(migrated, "SELECT activeRevision FROM sources WHERE id = 'source-1'", 1)
        assertSchemaObjectExists(migrated, "table", "epg_sources")
        assertSchemaObjectExists(migrated, "table", "epg_revisions")
        assertSchemaObjectExists(migrated, "table", "epg_channels")
        assertSchemaObjectExists(migrated, "table", "epg_programmes")
        assertSchemaObjectExists(migrated, "table", "epg_refresh_policies")
        assertSchemaObjectExists(migrated, "table", "epg_refresh_states")
        assertSchemaObjectExists(migrated, "table", "epg_refresh_attempts")
        assertSchemaObjectExists(migrated, "table", "epg_refresh_http_validators")
        assertSchemaObjectExists(migrated, "index", "index_epg_refresh_attempts_sourceId")
        assertSchemaObjectExists(
            migrated,
            "index",
            "index_epg_refresh_attempts_sourceId_startedAtEpochMillis",
        )
        assertSchemaObjectExists(migrated, "index", "index_epg_refresh_attempts_runToken")

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            "INSERT INTO epg_sources(" +
                "id, name, providerSourceId, accessRef, defaultZoneId, activeRevision" +
                ") VALUES('epg-source-1', 'Guide', 'source-1', 'opaque-access-ref', 'UTC', 0)",
        )
        migrated.execSQL(
            "INSERT INTO epg_refresh_policies(" +
                "sourceId, enabled, intervalMinutes, unmeteredOnly, requiresCharging, updatedAtEpochMillis" +
                ") VALUES('epg-source-1', 1, 60, 0, 0, 100)",
        )
        migrated.execSQL(
            "INSERT INTO epg_refresh_states(" +
                "sourceId, state, runToken, startedAtEpochMillis, completedAtEpochMillis, " +
                "lastSuccessRevision, lastSuccessAtEpochMillis, resultFamily, resultCode, httpStatus" +
                ") VALUES('epg-source-1', 'SUCCEEDED', NULL, 90, 100, NULL, 100, " +
                "'EPG_REFRESH', 'NOT_MODIFIED', 304)",
        )
        migrated.execSQL(
            "INSERT INTO epg_refresh_attempts(" +
                "sourceId, runToken, trigger, startedAtEpochMillis, completedAtEpochMillis, " +
                "resultState, resultFamily, resultCode, revisionNumber, channelCount, programmeCount, " +
                "skippedProgrammeCount, warningCount, unresolvedTimeCount, httpStatus" +
                ") VALUES('epg-source-1', 'run-1', 'PERIODIC', 90, 100, 'SUCCEEDED', " +
                "'EPG_REFRESH', 'NOT_MODIFIED', NULL, NULL, NULL, NULL, NULL, NULL, 304)",
        )
        migrated.execSQL(
            "INSERT INTO epg_refresh_http_validators(" +
                "sourceId, accessRefBinding, etag, lastModified, updatedAtEpochMillis" +
                ") VALUES('epg-source-1', 'opaque-access-ref', 'synthetic-etag', " +
                "'synthetic-last-modified', 100)",
        )

        migrated.execSQL("DELETE FROM epg_sources WHERE id = 'epg-source-1'")
        assertSingleLong(migrated, "SELECT COUNT(*) FROM epg_refresh_policies", 0)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM epg_refresh_states", 0)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM epg_refresh_attempts", 0)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM epg_refresh_http_validators", 0)
        migrated.close()
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
        const val DATABASE_NAME = "epg-migration-4-6-chain.db"
    }
}

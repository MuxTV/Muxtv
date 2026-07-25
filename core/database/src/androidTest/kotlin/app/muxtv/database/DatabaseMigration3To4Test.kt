package app.muxtv.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigration3To4Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(TEST_DATABASE),
        driver = AndroidSQLiteDriver(),
        databaseClass = MuxTvDatabase::class,
    )

    @Before
    fun clearDatabase() {
        instrumentation.targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration3To4PreservesExistingSourceStateAndCreatesSafePendingRegistry() = runTest {
        migrationHelper.createDatabase(version = 3).use { database ->
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL(
                """
                INSERT INTO sources(id, name, credentialRef, activeRevision)
                VALUES('source-existing', 'Existing source', 'credential-opaque', 7)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO source_revisions(
                    sourceId,
                    revisionNumber,
                    status,
                    startedAtEpochMillis,
                    activatedAtEpochMillis,
                    parsedEntries,
                    skippedEntries,
                    warningCount
                ) VALUES(
                    'source-existing',
                    7,
                    'ACTIVE',
                    1000,
                    2000,
                    25,
                    2,
                    1
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO source_refresh_policies(
                    sourceId,
                    enabled,
                    intervalMinutes,
                    unmeteredOnly,
                    requiresCharging,
                    updatedAtEpochMillis
                ) VALUES(
                    'source-existing',
                    1,
                    60,
                    1,
                    0,
                    3000
                )
                """.trimIndent(),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            version = 4,
            migrations = listOf(MIGRATION_3_4),
        ).use { database ->
            database.prepare(
                """
                SELECT name, credentialRef, activeRevision
                FROM sources
                WHERE id = 'source-existing'
                """.trimIndent(),
            ).use { statement ->
                assertThat(statement.step()).isTrue()
                assertThat(statement.getText(0)).isEqualTo("Existing source")
                assertThat(statement.getText(1)).isEqualTo("credential-opaque")
                assertThat(statement.getLong(2)).isEqualTo(7L)
                assertThat(statement.step()).isFalse()
            }

            database.prepare(
                """
                SELECT status, parsedEntries, skippedEntries, warningCount
                FROM source_revisions
                WHERE sourceId = 'source-existing' AND revisionNumber = 7
                """.trimIndent(),
            ).use { statement ->
                assertThat(statement.step()).isTrue()
                assertThat(statement.getText(0)).isEqualTo("ACTIVE")
                assertThat(statement.getLong(1)).isEqualTo(25L)
                assertThat(statement.getLong(2)).isEqualTo(2L)
                assertThat(statement.getLong(3)).isEqualTo(1L)
                assertThat(statement.step()).isFalse()
            }

            database.prepare(
                """
                SELECT enabled, intervalMinutes, unmeteredOnly, requiresCharging
                FROM source_refresh_policies
                WHERE sourceId = 'source-existing'
                """.trimIndent(),
            ).use { statement ->
                assertThat(statement.step()).isTrue()
                assertThat(statement.getLong(0)).isEqualTo(1L)
                assertThat(statement.getLong(1)).isEqualTo(60L)
                assertThat(statement.getLong(2)).isEqualTo(1L)
                assertThat(statement.getLong(3)).isEqualTo(0L)
                assertThat(statement.step()).isFalse()
            }

            assertThat(database.querySingleLong("SELECT COUNT(*) FROM pending_source_preparations"))
                .isEqualTo(0L)
            assertThat(database.tableColumns("pending_source_preparations")).containsExactly(
                "preparationId",
                "scheme",
                "host",
                "createdAtEpochMillis",
                "expiresAtEpochMillis",
            ).inOrder()
            assertThat(database.tableIndexes("pending_source_preparations"))
                .contains("index_pending_source_preparations_expiresAtEpochMillis")
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-3-4-test.db"
    }
}

private fun SQLiteConnection.querySingleLong(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step()) { "Query returned no rows." }
    statement.getLong(0)
}

private fun SQLiteConnection.tableColumns(tableName: String): List<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildList {
            while (statement.step()) {
                add(statement.getText(1))
            }
        }
    }

private fun SQLiteConnection.tableIndexes(tableName: String): List<String> =
    prepare("PRAGMA index_list(`$tableName`)").use { statement ->
        buildList {
            while (statement.step()) {
                add(statement.getText(1))
            }
        }
    }

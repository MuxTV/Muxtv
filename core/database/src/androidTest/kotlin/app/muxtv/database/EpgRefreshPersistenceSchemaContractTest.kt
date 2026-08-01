package app.muxtv.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRefreshPersistenceSchemaContractTest {
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
    fun currentSchemaProvidesIsolatedDurableEpgRefreshTables() {
        val database = migrationHelper.createDatabase(5)

        assertSchemaObjectExists(database, "table", "epg_refresh_policies")
        assertSchemaObjectExists(database, "table", "epg_refresh_states")
        assertSchemaObjectExists(database, "table", "epg_refresh_attempts")
        assertSchemaObjectExists(database, "table", "epg_refresh_http_validators")

        assertColumnAbsent(database, "epg_refresh_states", "etag")
        assertColumnAbsent(database, "epg_refresh_states", "lastModified")
        assertColumnAbsent(database, "epg_refresh_attempts", "etag")
        assertColumnAbsent(database, "epg_refresh_attempts", "lastModified")

        database.close()
    }

    private fun assertSchemaObjectExists(
        connection: SQLiteConnection,
        type: String,
        name: String,
    ) {
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

    private fun assertColumnAbsent(
        connection: SQLiteConnection,
        table: String,
        column: String,
    ) {
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            while (statement.step()) {
                assertThat(statement.getText(1)).isNotEqualTo(column)
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "epg-refresh-persistence-schema.db"
    }
}

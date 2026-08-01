package app.muxtv.database

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRefreshPersistenceSchemaContractTest {
    private lateinit var database: MuxTvDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun currentSchemaProvidesIsolatedDurableEpgRefreshTables() = runTest {
        database.useReaderConnection { connection ->
            assertSchemaObjectExists(connection, "table", "epg_refresh_policies")
            assertSchemaObjectExists(connection, "table", "epg_refresh_states")
            assertSchemaObjectExists(connection, "table", "epg_refresh_attempts")
            assertSchemaObjectExists(connection, "table", "epg_refresh_http_validators")

            assertColumnAbsent(connection, "epg_refresh_states", "etag")
            assertColumnAbsent(connection, "epg_refresh_states", "lastModified")
            assertColumnAbsent(connection, "epg_refresh_attempts", "etag")
            assertColumnAbsent(connection, "epg_refresh_attempts", "lastModified")
        }
    }

    private suspend fun assertSchemaObjectExists(
        connection: androidx.room3.Transactor,
        type: String,
        name: String,
    ) {
        connection.usePrepared(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
        ) { statement ->
            statement.bindText(1, type)
            statement.bindText(2, name)
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(1)
            assertThat(statement.step()).isFalse()
        }
    }

    private suspend fun assertColumnAbsent(
        connection: androidx.room3.Transactor,
        table: String,
        column: String,
    ) {
        connection.usePrepared("PRAGMA table_info(`$table`)") { statement ->
            while (statement.step()) {
                assertThat(statement.getText(1)).isNotEqualTo(column)
            }
        }
    }
}

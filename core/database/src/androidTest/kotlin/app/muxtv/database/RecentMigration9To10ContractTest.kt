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
class RecentMigration9To10ContractTest {
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
    fun migrationAddsEmptyRecentTableWithoutChangingExistingCatalogTruth() = runBlocking {
        val version9 = migrationHelper.createDatabase(9)
        version9.execSQL("PRAGMA foreign_keys = OFF")
        version9.execSQL(
            "INSERT INTO profiles(id, name, isPrimary, archivedAtEpochMillis) " +
                "VALUES('profile-main', 'Primary', 1, NULL)",
        )
        version9.execSQL(
            "INSERT INTO canonical_channels(id, displayName) VALUES('channel-a', 'Channel A')",
        )
        version9.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 10,
            migrations = listOf(MIGRATION_9_10),
        )

        migrated.prepare("SELECT COUNT(*) FROM recent_channels").use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(0L)
        }
        migrated.prepare("SELECT COUNT(*) FROM profiles WHERE id = 'profile-main'").use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(1L)
        }
        migrated.prepare("SELECT COUNT(*) FROM canonical_channels WHERE id = 'channel-a'").use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(1L)
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "recent-migration-9-10-contract.db"
    }
}

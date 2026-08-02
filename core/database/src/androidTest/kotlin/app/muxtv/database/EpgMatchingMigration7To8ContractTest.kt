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
class EpgMatchingMigration7To8ContractTest {
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
    fun v7RowsBecomeExplicitlyStaleInsteadOfBeingRelabeledCurrent() = runBlocking {
        val version7 = migrationHelper.createDatabase(7)
        version7.execSQL("PRAGMA foreign_keys = OFF")
        version7.execSQL(
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
                'legacy-epg', 1, 'legacy-provider', 1, 'legacy-channel',
                'UNRESOLVED', 'NO_MATCH', NULL, 0
            )
            """.trimIndent(),
        )

        MIGRATION_7_8.migrate(version7)

        version7.prepare(
            """
            SELECT matchPolicyVersion, decision, reasonCode
            FROM epg_channel_matches
            WHERE epgSourceId = 'legacy-epg'
            """.trimIndent(),
        ).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(
                LEGACY_UNVERSIONED_MATCH_POLICY_VERSION.toLong(),
            )
            assertThat(statement.getText(1)).isEqualTo("UNRESOLVED")
            assertThat(statement.getText(2)).isEqualTo("NO_MATCH")
            assertThat(statement.step()).isFalse()
        }
        version7.close()
    }

    private companion object {
        const val DATABASE_NAME = "epg-matching-migration-7-8-contract.db"
    }
}

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
class CatalogPayloadMigration10To11ContractTest {
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
    fun migrationBackfillsReusablePayloadsMembershipsAndLegacyRevisionZero() = runBlocking {
        val version10 = migrationHelper.createDatabase(10)
        version10.execSQL("PRAGMA foreign_keys = OFF")

        version10.execSQL(
            "INSERT INTO sources(id, name, credentialRef, activeRevision) " +
                "VALUES('source-main', 'Main', NULL, 7)",
        )
        version10.execSQL(
            "INSERT INTO source_revisions(" +
                "sourceId, revisionNumber, status, startedAtEpochMillis, activatedAtEpochMillis, " +
                "parsedEntries, skippedEntries, warningCount" +
                ") VALUES('source-main', 6, 'RETAINED', 1, 2, 1, 0, 0)",
        )
        version10.execSQL(
            "INSERT INTO source_revisions(" +
                "sourceId, revisionNumber, status, startedAtEpochMillis, activatedAtEpochMillis, " +
                "parsedEntries, skippedEntries, warningCount" +
                ") VALUES('source-main', 7, 'ACTIVE', 3, 4, 1, 0, 0)",
        )
        version10.execSQL(
            "INSERT INTO canonical_channels(id, displayName) VALUES('channel-main', 'Main Channel')",
        )
        insertProvider(
            version10,
            id = "provider-r6",
            sourceId = "source-main",
            revision = 6,
            providerKey = "tvg:main",
            rawName = "Main Channel",
        )
        insertProvider(
            version10,
            id = "provider-r7",
            sourceId = "source-main",
            revision = 7,
            providerKey = "tvg:main",
            rawName = "Main Channel",
        )
        insertVariant(
            version10,
            id = "variant-r6",
            providerId = "provider-r6",
            canonicalId = "channel-main",
            locator = "https://stream.invalid/main",
        )
        insertVariant(
            version10,
            id = "variant-r7",
            providerId = "provider-r7",
            canonicalId = "channel-main",
            locator = "https://stream.invalid/main",
        )

        // Historical 1->2 migration could leave visible revision-0 catalog rows without matching
        // source_revisions metadata. v11 must preserve that truth rather than invent revision 0.
        version10.execSQL(
            "INSERT INTO sources(id, name, credentialRef, activeRevision) " +
                "VALUES('source-legacy', 'Legacy', NULL, 0)",
        )
        version10.execSQL(
            "INSERT INTO canonical_channels(id, displayName) VALUES('channel-legacy', 'Legacy')",
        )
        insertProvider(
            version10,
            id = "provider-r0",
            sourceId = "source-legacy",
            revision = 0,
            providerKey = "tvg:legacy",
            rawName = "Legacy",
        )
        insertVariant(
            version10,
            id = "variant-r0",
            providerId = "provider-r0",
            canonicalId = "channel-legacy",
            locator = "https://stream.invalid/legacy",
        )
        version10.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 11,
            migrations = listOf(MIGRATION_10_11),
        )

        assertSingleLong(migrated, "SELECT COUNT(*) FROM catalog_payloads", 2L)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM catalog_search_payloads", 2L)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM source_revision_memberships", 3L)

        // Identical retained/active payload content is physically reused.
        assertSingleLong(
            migrated,
            "SELECT COUNT(DISTINCT payloadId) FROM source_revision_memberships " +
                "WHERE sourceId = 'source-main'",
            1L,
        )
        assertSingleLong(
            migrated,
            "SELECT COUNT(*) FROM source_revision_memberships " +
                "WHERE sourceId = 'source-legacy' AND revisionNumber = 0",
            1L,
        )
        assertSingleLong(
            migrated,
            "SELECT activeRevision FROM sources WHERE id = 'source-legacy'",
            0L,
        )
        assertSingleLong(
            migrated,
            "SELECT COUNT(*) FROM source_revisions " +
                "WHERE sourceId = 'source-legacy' AND revisionNumber = 0",
            0L,
        )

        assertSingleText(
            migrated,
            "SELECT variantId FROM source_revision_memberships " +
                "WHERE sourceId = 'source-main' AND revisionNumber = 6",
            "variant-r6",
        )
        assertSingleText(
            migrated,
            "SELECT variantId FROM source_revision_memberships " +
                "WHERE sourceId = 'source-main' AND revisionNumber = 7",
            "variant-r7",
        )
        assertSingleText(
            migrated,
            "SELECT variantId FROM source_revision_memberships " +
                "WHERE sourceId = 'source-legacy' AND revisionNumber = 0",
            "variant-r0",
        )

        // Additive migration leaves the v10 authoritative representation intact.
        assertSingleLong(migrated, "SELECT COUNT(*) FROM provider_channels", 3L)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM stream_variants", 3L)
        migrated.close()
    }

    private fun insertProvider(
        connection: androidx.sqlite.SQLiteConnection,
        id: String,
        sourceId: String,
        revision: Long,
        providerKey: String,
        rawName: String,
    ) {
        connection.prepare(
            """
            INSERT INTO provider_channels(
                id, sourceId, revisionNumber, providerKey, rawName,
                tvgId, tvgName, logoUrl, groupTitle, channelNumber,
                catchupMode, catchupSource, catchupDays, catchupCorrection
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, NULL, NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, id)
            statement.bindText(2, sourceId)
            statement.bindLong(3, revision)
            statement.bindText(4, providerKey)
            statement.bindText(5, rawName)
            statement.bindText(6, providerKey.removePrefix("tvg:"))
            statement.bindText(7, rawName)
            statement.bindText(8, "General")
            statement.bindText(9, "1")
            statement.step()
        }
    }

    private fun insertVariant(
        connection: androidx.sqlite.SQLiteConnection,
        id: String,
        providerId: String,
        canonicalId: String,
        locator: String,
    ) {
        connection.prepare(
            """
            INSERT INTO stream_variants(
                id, providerChannelId, canonicalChannelId, locator, userAgent, referrer
            ) VALUES (?, ?, ?, ?, NULL, NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, id)
            statement.bindText(2, providerId)
            statement.bindText(3, canonicalId)
            statement.bindText(4, locator)
            statement.step()
        }
    }

    private fun assertSingleLong(
        connection: androidx.sqlite.SQLiteConnection,
        query: String,
        expected: Long,
    ) {
        connection.prepare(query).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getLong(0)).isEqualTo(expected)
            assertThat(statement.step()).isFalse()
        }
    }

    private fun assertSingleText(
        connection: androidx.sqlite.SQLiteConnection,
        query: String,
        expected: String,
    ) {
        connection.prepare(query).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getText(0)).isEqualTo(expected)
            assertThat(statement.step()).isFalse()
        }
    }

    private companion object {
        const val DATABASE_NAME = "catalog-payload-migration-10-11-contract.db"
    }
}

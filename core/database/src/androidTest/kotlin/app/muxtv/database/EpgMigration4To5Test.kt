package app.muxtv.database

import androidx.room3.Room
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
class EpgMigration4To5Test {
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
    fun migrationPreservesCatalogAndEnablesAtomicEpgActivation() = runBlocking {
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
        version4.execSQL(
            "INSERT INTO source_revisions(" +
                "sourceId, revisionNumber, status, startedAtEpochMillis, activatedAtEpochMillis, " +
                "parsedEntries, skippedEntries, warningCount" +
                ") VALUES('source-1', 1, 'ACTIVE', 10, 20, 1, 0, 0)",
        )
        version4.execSQL(
            "INSERT INTO canonical_channels(id, displayName) VALUES('canonical-1', 'Channel One')",
        )
        version4.execSQL(
            "INSERT INTO provider_channels(" +
                "id, sourceId, revisionNumber, providerKey, rawName, tvgId, tvgName, logoUrl, " +
                "groupTitle, channelNumber, catchupMode, catchupSource, catchupDays, catchupCorrection" +
                ") VALUES(" +
                "'provider-1', 'source-1', 1, 'provider-key-1', 'Channel One', NULL, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, NULL" +
                ")",
        )
        version4.execSQL(
            "INSERT INTO stream_variants(" +
                "id, providerChannelId, canonicalChannelId, locator, userAgent, referrer" +
                ") VALUES(" +
                "'variant-1', 'provider-1', 'canonical-1', 'https://example.invalid/live.m3u8', NULL, NULL" +
                ")",
        )
        version4.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 5,
            migrations = listOf(MIGRATION_4_5),
        )
        assertSingleText(migrated, "SELECT name FROM profiles WHERE id = 'profile-1'", "Primary")
        assertSingleLong(migrated, "SELECT activeRevision FROM sources WHERE id = 'source-1'", 1)
        assertSingleText(
            migrated,
            "SELECT displayName FROM canonical_channels WHERE id = 'canonical-1'",
            "Channel One",
        )
        assertSingleText(
            migrated,
            "SELECT locator FROM stream_variants WHERE id = 'variant-1'",
            "https://example.invalid/live.m3u8",
        )
        assertSchemaObjectExists(migrated, "table", "epg_sources")
        assertSchemaObjectExists(migrated, "table", "epg_revisions")
        assertSchemaObjectExists(migrated, "table", "epg_channels")
        assertSchemaObjectExists(migrated, "table", "epg_programmes")
        assertSchemaObjectExists(migrated, "index", "index_epg_revisions_sourceId_status")
        assertSchemaObjectExists(
            migrated,
            "index",
            "index_epg_programmes_sourceId_revisionNumber_externalChannelId_startEpochMillis",
        )
        assertSchemaObjectExists(
            migrated,
            "index",
            "index_epg_programmes_sourceId_revisionNumber_startEpochMillis_stopEpochMillis",
        )
        migrated.close()

        val database = Room.databaseBuilder(
            context = targetContext,
            klass = MuxTvDatabase::class.java,
            name = DATABASE_NAME,
        )
            .addMigrations(MIGRATION_4_5)
            .build()
        migrationHelper.closeWhenFinished(database)
        val dao = database.epgRevisionDao()

        dao.insertSource(
            EpgSourceEntity(
                id = "epg-source-1",
                name = "Guide",
                providerSourceId = "source-1",
                accessRef = "opaque-access-ref",
                defaultZoneId = null,
            ),
        )
        dao.insertRevision(
            EpgRevisionEntity(
                sourceId = "epg-source-1",
                revisionNumber = 1,
                status = EpgRevisionEntity.STATUS_STAGING,
                startedAtEpochMillis = 30,
            ),
        )
        dao.stageBatch(
            channels = listOf(
                EpgChannelEntity(
                    sourceId = "epg-source-1",
                    revisionNumber = 1,
                    externalId = "channel-one",
                    primaryDisplayName = "Channel One",
                    primaryLanguage = "en",
                    iconRef = null,
                ),
            ),
            programmes = listOf(
                EpgProgrammeEntity(
                    sourceId = "epg-source-1",
                    revisionNumber = 1,
                    sequenceNumber = 1,
                    externalChannelId = "channel-one",
                    startEpochMillis = 1_000,
                    stopEpochMillis = 2_000,
                    primaryTitle = "Programme",
                    primaryLanguage = "en",
                    subtitle = null,
                    description = null,
                    category = null,
                    iconRef = null,
                    episodeNumber = null,
                    isNew = false,
                ),
            ),
        )

        assertThat(
            dao.activeProgrammes(
                sourceId = "epg-source-1",
                externalChannelIds = listOf("channel-one"),
                fromEpochMillis = 0,
                toEpochMillis = 10_000,
                limit = 20,
            ),
        ).isEmpty()

        assertThat(
            dao.activateRevision(
                sourceId = "epg-source-1",
                revisionNumber = 1,
                activatedAtEpochMillis = 40,
                statistics = EpgRevisionStatistics(
                    acceptedChannels = 1,
                    acceptedProgrammes = 1,
                    skippedProgrammes = 0,
                    warningCount = 0,
                    unresolvedTimeCount = 0,
                ),
            ),
        ).isEqualTo(
            EpgRevisionActivationResult.Activated(
                revisionNumber = 1,
                previousRevisionNumber = 0,
                programmeCount = 1,
            ),
        )
        assertThat(dao.activeRevision("epg-source-1")).isEqualTo(1)
        assertThat(
            dao.activeProgrammes(
                sourceId = "epg-source-1",
                externalChannelIds = listOf("channel-one"),
                fromEpochMillis = 0,
                toEpochMillis = 10_000,
                limit = 20,
            ),
        ).hasSize(1)
    }

    private fun assertSingleText(connection: SQLiteConnection, query: String, expected: String) {
        connection.prepare(query).use { statement ->
            assertThat(statement.step()).isTrue()
            assertThat(statement.getText(0)).isEqualTo(expected)
            assertThat(statement.step()).isFalse()
        }
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
        const val DATABASE_NAME = "epg-migration-4-5.db"
    }
}

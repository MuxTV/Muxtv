package app.muxtv.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
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


    @Test
    fun migratedUserStateSurvivesRefreshDiscardAndStaleCredential() = runBlocking {
        val version10 = migrationHelper.createDatabase(10)
        version10.execSQL(
            "INSERT INTO profiles(id, name, isPrimary, archivedAtEpochMillis) " +
                "VALUES('profile-upgrade', 'Primary', 1, NULL)",
        )
        version10.execSQL(
            "INSERT INTO sources(id, name, credentialRef, activeRevision) " +
                "VALUES('source-upgrade', 'Provider', NULL, 1)",
        )
        version10.execSQL(
            "INSERT INTO source_revisions(" +
                "sourceId, revisionNumber, status, startedAtEpochMillis, activatedAtEpochMillis, " +
                "parsedEntries, skippedEntries, warningCount" +
                ") VALUES('source-upgrade', 1, 'ACTIVE', 1000, 1100, 1, 0, 0)",
        )
        version10.execSQL(
            "INSERT INTO canonical_channels(id, displayName) " +
                "VALUES('channel-upgrade', 'Channel One')",
        )
        insertProvider(
            version10,
            id = "provider-upgrade-r1",
            sourceId = "source-upgrade",
            revision = 1,
            providerKey = "tvg:channel-one",
            rawName = "Channel One",
        )
        insertVariant(
            version10,
            id = "variant-upgrade-r1",
            providerId = "provider-upgrade-r1",
            canonicalId = "channel-upgrade",
            locator = "https://stream.invalid/live?token=one",
        )
        version10.execSQL(
            "INSERT INTO user_channel_overlays(" +
                "profileId, canonicalChannelId, isFavorite, customName, channelNumber, isHidden" +
                ") VALUES('profile-upgrade', 'channel-upgrade', 1, 'My News', 77, 1)",
        )
        version10.execSQL(
            "INSERT INTO recent_channels(" +
                "profileId, canonicalChannelId, lastSuccessfulPlaybackAtEpochMillis" +
                ") VALUES('profile-upgrade', 'channel-upgrade', 42000)",
        )
        assertPersistedUserState(version10)
        version10.close()

        // Migrate the *same on-disk file*, rather than seeding a fresh v11 in-memory database.
        val migrated = migrationHelper.runMigrationsAndValidate(
            version = 11,
            migrations = listOf(MIGRATION_10_11),
        )
        assertPersistedUserState(migrated)
        assertSingleLong(migrated, "SELECT COUNT(*) FROM source_revision_memberships", 1L)
        migrated.close()

        val database = Room.databaseBuilder(
            context = targetContext,
            klass = MuxTvDatabase::class.java,
            name = DATABASE_NAME,
        ).addMigrations(*CURRENT_DATABASE_MIGRATIONS).build()
        try {
            val store = RoomSourceRevisionStore(database.sourceRevisionDao())
            assertRoomUserState(database)
            assertThat(database.catalogShadowDao().activeRows("source-upgrade").single().variantId)
                .isEqualTo("variant-upgrade-r1")

            // Failed/abandoned staging must not replace the active revision or user state.
            store.beginRevision("source-upgrade", 2, 2_000)
            store.stageBatch("source-upgrade", 2, listOf(upgradedEntry(2)))
            store.discard("source-upgrade", 2)
            assertRoomUserState(database)
            assertThat(database.catalogShadowDao().activeRows("source-upgrade").single().variantId)
                .isEqualTo("variant-upgrade-r1")

            // Successful refresh changes only the source-owned occurrence and immutable payload.
            store.beginRevision("source-upgrade", 3, 3_000)
            store.stageBatch("source-upgrade", 3, listOf(upgradedEntry(3)))
            assertThat(
                store.activate(
                    sourceId = "source-upgrade",
                    revisionNumber = 3,
                    activatedAtEpochMillis = 3_100,
                    statistics = SourceRevisionStatistics(1, 0, 0),
                ),
            ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
            assertRoomUserState(database)
            assertThat(database.catalogShadowDao().activeRows("source-upgrade").single().variantId)
                .isEqualTo("variant-upgrade-r3")
            assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(2)

            // A stale credential cannot publish a later staged revision after the upgrade.
            store.beginRevision("source-upgrade", 4, 4_000)
            store.stageBatch("source-upgrade", 4, listOf(upgradedEntry(4)))
            assertThat(
                store.activateIfCredentialMatches(
                    sourceId = "source-upgrade",
                    revisionNumber = 4,
                    expectedCredentialRef = "stale-credential-ref",
                    activatedAtEpochMillis = 4_100,
                    statistics = SourceRevisionStatistics(1, 0, 0),
                ),
            ).isEqualTo(SourceRevisionActivationResult.Superseded)
            assertRoomUserState(database)
            assertThat(database.catalogShadowDao().activeRows("source-upgrade").single().variantId)
                .isEqualTo("variant-upgrade-r3")
            assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(2)
        } finally {
            database.close()
        }
    }

    private fun upgradedEntry(revision: Int) = StagedCatalogEntry(
        providerChannelId = "provider-upgrade-r$revision",
        providerKey = "tvg:channel-one",
        rawName = "Channel One",
        canonicalChannelId = "channel-upgrade",
        canonicalDisplayName = "Channel One",
        streamVariantId = "variant-upgrade-r$revision",
        locator = "https://stream.invalid/live?token=r$revision",
        tvgId = "channel-one",
        tvgName = "Channel One",
        groupTitle = "General",
        channelNumber = "1",
    )

    private suspend fun assertRoomUserState(database: MuxTvDatabase) {
        database.useReaderConnection { connection ->
            assertPersistedUserState(connection)
        }
    }

    private fun assertPersistedUserState(connection: androidx.sqlite.SQLiteConnection) {
        assertSingleLong(
            connection,
            "SELECT COUNT(*) FROM canonical_channels WHERE id = 'channel-upgrade'",
            1L,
        )
        assertSingleLong(
            connection,
            "SELECT isFavorite FROM user_channel_overlays " +
                "WHERE profileId = 'profile-upgrade' AND canonicalChannelId = 'channel-upgrade'",
            1L,
        )
        assertSingleLong(
            connection,
            "SELECT isHidden FROM user_channel_overlays " +
                "WHERE profileId = 'profile-upgrade' AND canonicalChannelId = 'channel-upgrade'",
            1L,
        )
        assertSingleText(
            connection,
            "SELECT customName FROM user_channel_overlays " +
                "WHERE profileId = 'profile-upgrade' AND canonicalChannelId = 'channel-upgrade'",
            "My News",
        )
        assertSingleLong(
            connection,
            "SELECT channelNumber FROM user_channel_overlays " +
                "WHERE profileId = 'profile-upgrade' AND canonicalChannelId = 'channel-upgrade'",
            77L,
        )
        assertSingleLong(
            connection,
            "SELECT lastSuccessfulPlaybackAtEpochMillis FROM recent_channels " +
                "WHERE profileId = 'profile-upgrade' AND canonicalChannelId = 'channel-upgrade'",
            42_000L,
        )
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

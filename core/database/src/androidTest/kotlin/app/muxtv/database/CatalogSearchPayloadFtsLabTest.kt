package app.muxtv.database

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D3-A feasibility oracle, deliberately isolated from the production Room schema.
 *
 * Rebuild a separate FTS4/unicode61 index from active immutable v11 provider-search
 * payloads plus canonical display metadata and profile-owned overlays. Compare provider,
 * group, number, canonical name and overlay ranks to the existing production FTS policy
 * before/after staging, activation, old-revision retention and per-profile hiding.
 *
 * This does NOT add a persistent v11 Search table or independently index EPG programmes,
 * and does not authorize a production Search cutover. A persistent index requires its own
 * schema migration, publication contract and write/WAL measurement.
 */
@RunWith(AndroidJUnit4::class)
class CatalogSearchPayloadFtsLabTest {
    @Test
    fun activeImmutableSearchPayloadsProduceIndependentUnicode61CandidateParity() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        val lab = AndroidSQLiteDriver().open(":memory:")
        try {
            DatabaseInitializer(database).initialize()
            database.profileDao().insert(
                ProfileEntity(id = SECONDARY_PROFILE, name = "Secondary", isPrimary = false),
            )
            lab.execSQL(
                """
                CREATE VIRTUAL TABLE lab_provider_fts USING fts4(
                    searchPayloadId,
                    canonicalChannelId,
                    documentKind,
                    text,
                    notindexed=searchPayloadId,
                    notindexed=canonicalChannelId,
                    notindexed=documentKind,
                    tokenize=unicode61
                )
                """.trimIndent(),
            )

            val store = RoomSourceRevisionStore(database.sourceRevisionDao())
            store.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
            store.beginRevision(SOURCE_ID, 1, 1_000)
            store.stageBatch(
                SOURCE_ID, 1, listOf(
                    entry(1, "a", "Россия Первый", "Новости HD", "001"),
                    entry(1, "b", "Спорт Live", "Новости HD", "002"),
                ),
            )

            // Staging may already have provider FTS rows in v10; neither active view
            // may expose them before a successful publication.
            assertProviderParity(database, lab, PRIMARY_PROFILE)
            activate(store, 1, 2)
            assertProviderParity(database, lab, PRIMARY_PROFILE)
            assertProviderParity(database, lab, SECONDARY_PROFILE)

            // A newly staged revision must not expose any of its changed words or numbers.
            store.beginRevision(SOURCE_ID, 2, 3_000)
            store.stageBatch(
                SOURCE_ID, 2, listOf(
                    entry(2, "a", "Вечернее Радио", "Музыка", "077"),
                    entry(2, "b", "Спорт Live", "Новости HD", "002"),
                ),
            )
            assertProviderParity(database, lab, PRIMARY_PROFILE)
            activate(store, 2, 2)
            assertProviderParity(database, lab, PRIMARY_PROFILE)

            // Overlay name is a NAME-rank document (3); an overlay number is a
            // PROVIDER-rank document (4). Neither belongs to the secondary profile.
            database.catalogDao().insertOverlay(
                UserChannelOverlayEntity(
                    profileId = PRIMARY_PROFILE,
                    canonicalChannelId = CHANNEL_A,
                    customName = "Личный Эфир",
                    channelNumber = 88,
                ),
            )
            assertProviderParity(database, lab, PRIMARY_PROFILE)
            assertProviderParity(database, lab, SECONDARY_PROFILE)

            // Hiding a channel suppresses canonical, overlay and provider matches for
            // the primary profile while the secondary profile keeps its own visibility.
            database.catalogDao().insertOverlay(
                UserChannelOverlayEntity(
                    profileId = PRIMARY_PROFILE,
                    canonicalChannelId = CHANNEL_A,
                    customName = "Личный Эфир",
                    channelNumber = 88,
                    isHidden = true,
                ),
            )
            assertProviderParity(database, lab, PRIMARY_PROFILE)
            assertProviderParity(database, lab, SECONDARY_PROFILE)

            // A later publication eliminates the original canonical from the active
            // membership even though the retained revision and its payload may still exist.
            store.beginRevision(SOURCE_ID, 3, 5_000)
            store.stageBatch(SOURCE_ID, 3, listOf(entry(3, "b", "Спорт Live", "Новости HD", "002")))
            activate(store, 3, 1)
            assertProviderParity(database, lab, SECONDARY_PROFILE)
            assertThat(readActiveSearchPayloads(database, SECONDARY_PROFILE)
                .map { it.canonicalId }).containsExactly(CHANNEL_B)
        } finally {
            lab.close()
            database.close()
        }
    }

    private suspend fun assertProviderParity(
        database: MuxTvDatabase,
        lab: SQLiteConnection,
        profileId: String,
    ) {
        val live = readActiveSearchPayloads(database, profileId)
        lab.execSQL("DELETE FROM lab_provider_fts")
        live.forEach { payload ->
            append(lab, payload.id, payload.canonicalId, "RAW", payload.rawName)
            append(lab, payload.id, payload.canonicalId, "GROUP", payload.groupTitle)
            append(lab, payload.id, payload.canonicalId, "NUMBER", payload.channelNumber)
            // Canonical metadata is published by activation and may match the same
            // token as provider RAW. The minimum rank must be 3, not provider rank 4.
            append(lab, payload.id, payload.canonicalId, "CANONICAL", payload.canonicalName)
            append(lab, payload.id, payload.canonicalId, "OVERLAY_NAME", payload.overlayName)
            append(lab, payload.id, payload.canonicalId, "OVERLAY_NUMBER", payload.overlayNumber)
        }

        // EPG remains out of this oracle; this fixture has no EPG publications.
        // The real SearchQueryEncoder supplies safe prefix syntax to both engines.
        listOf("рос", "перв", "нов", "спор", "live", "001", "002",
            "вече", "ради", "муз", "077", "лич", "эфи", "88",
            "несуществующий").forEach { probe ->
            val token = SearchQueryEncoder.encode(probe).single().ftsExpression
            val actual = database.channelSearchDao().searchCandidates(
                profileId = profileId,
                ftsExpression = token,
                nowEpochMillis = 1_500,
                fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
            )
            val expected = probeFts(lab, token)
            assertThat(actual).containsExactlyElementsIn(expected).inOrder()
        }
    }

    private suspend fun readActiveSearchPayloads(
        database: MuxTvDatabase,
        profileId: String,
    ): List<ActiveSearchPayload> = database.useReaderConnection { transactor ->
        transactor.usePrepared(
            """
            SELECT DISTINCT sp.searchPayloadId, sp.canonicalChannelId,
                   sp.rawName, COALESCE(sp.groupTitle, ''), COALESCE(sp.channelNumber, ''),
                   canonical.displayName, COALESCE(overlay.customName, ''),
                   COALESCE(CAST(overlay.channelNumber AS TEXT), '')
            FROM catalog_search_payloads AS sp
            INNER JOIN canonical_channels AS canonical
                ON canonical.id = sp.canonicalChannelId
            INNER JOIN catalog_payloads AS p ON p.searchPayloadId = sp.searchPayloadId
            INNER JOIN source_revision_memberships AS m ON m.payloadId = p.payloadId
            INNER JOIN sources AS src
                ON src.id = m.sourceId AND src.activeRevision = m.revisionNumber
            LEFT JOIN user_channel_overlays AS overlay
                ON overlay.profileId = ?
               AND overlay.canonicalChannelId = sp.canonicalChannelId
            WHERE COALESCE(overlay.isHidden, 0) = 0
            ORDER BY sp.searchPayloadId COLLATE BINARY
            """.trimIndent(),
        ) { stmt ->
            stmt.bindText(1, profileId)
            buildList {
                while (stmt.step()) {
                    add(
                        ActiveSearchPayload(
                            id = stmt.getText(0),
                            canonicalId = stmt.getText(1),
                            rawName = stmt.getText(2),
                            groupTitle = stmt.getText(3),
                            channelNumber = stmt.getText(4),
                            canonicalName = stmt.getText(5),
                            overlayName = stmt.getText(6),
                            overlayNumber = stmt.getText(7),
                        ),
                    )
                }
            }
        }
    }

    private fun append(
        index: SQLiteConnection,
        payloadId: String,
        canonicalId: String,
        kind: String,
        text: String,
    ) {
        if (text.isBlank()) return
        index.prepare(
            """
            INSERT INTO lab_provider_fts(searchPayloadId, canonicalChannelId, documentKind, text)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, payloadId)
            stmt.bindText(2, canonicalId)
            stmt.bindText(3, kind)
            stmt.bindText(4, text)
            stmt.step()
        }
    }

    private fun probeFts(
        lab: SQLiteConnection,
        expression: String,
    ): List<ChannelSearchCandidateRow> =
        lab.prepare(
            """
            SELECT canonicalChannelId,
                   MIN(CASE
                       WHEN documentKind IN ('CANONICAL', 'OVERLAY_NAME') THEN 3
                       WHEN documentKind = 'GROUP' THEN 5
                       ELSE 4
                   END) AS bestMatchRank
            FROM lab_provider_fts
            WHERE lab_provider_fts MATCH ?
            GROUP BY canonicalChannelId
            ORDER BY bestMatchRank, canonicalChannelId COLLATE BINARY
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, expression)
            buildList {
                while (stmt.step()) {
                    add(ChannelSearchCandidateRow(stmt.getText(0), stmt.getLong(1).toInt()))
                }
            }
        }

    private suspend fun activate(store: SourceRevisionStore, revision: Long, count: Int) {
        assertThat(
            store.activate(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                activatedAtEpochMillis = revision * 2_000,
                statistics = SourceRevisionStatistics(count, 0, 0),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun entry(
        revision: Int,
        channel: String,
        rawName: String,
        groupTitle: String,
        channelNumber: String,
    ) = StagedCatalogEntry(
        providerChannelId = "provider-r$revision-$channel",
        providerKey = "tvg:$channel",
        rawName = rawName,
        canonicalChannelId = if (channel == "a") CHANNEL_A else CHANNEL_B,
        canonicalDisplayName = "Station $channel",
        streamVariantId = "variant-r$revision-$channel",
        locator = "https://stream.invalid/$channel/r$revision",
        groupTitle = groupTitle,
        channelNumber = channelNumber,
    )

    private data class ActiveSearchPayload(
        val id: String,
        val canonicalId: String,
        val rawName: String,
        val groupTitle: String,
        val channelNumber: String,
        val canonicalName: String,
        val overlayName: String,
        val overlayNumber: String,
    )

    private companion object {
        const val SOURCE_ID = "source-fts-lab"
        const val PRIMARY_PROFILE = DatabaseDefaults.PRIMARY_PROFILE_ID
        const val SECONDARY_PROFILE = "profile-fts-lab-secondary"
        const val CHANNEL_A = "channel-fts-lab-a"
        const val CHANNEL_B = "channel-fts-lab-b"
    }
}

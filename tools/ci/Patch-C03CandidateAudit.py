from pathlib import Path

path = Path("core/database/src/debug/kotlin/app/muxtv/database/measurement/RefreshDeltaDatabaseMeasurementRunner.kt")
text = path.read_text()

old = '''                        RefreshDeltaDatabaseVariant.B_IMMUTABLE_COW -> {
                            val counts = samples.map { it.sample.observedRowMutations }.distinct()
                            check(counts.size == 1) { "C03 candidate row mutations were not deterministic." }
                            mapOf("sqlite.total_changes" to counts.single())
                        }'''
new = '''                        RefreshDeltaDatabaseVariant.B_IMMUTABLE_COW -> auditCandidateMutations(
                            scenario = scenario,
                            baseline = baseline,
                            incoming = incoming,
                        )'''
if text.count(old) != 1:
    raise SystemExit("candidate rowEvidence block not unique")
text = text.replace(old, new)

text = text.replace(
    '"A row mutations are counted in a separate untimed audit-trigger pass so counters cannot inflate timed WAL evidence.",',
    '"A and B row mutations are counted in separate untimed audit-trigger passes so counters cannot inflate timed WAL evidence.",',
)
text = text.replace("        val beforeChanges = totalChanges(database)\n", "")
text = text.replace("        val rowMutations = totalChanges(database) - beforeChanges\n", "")
text = text.replace("                observedRowMutations = rowMutations,", "                observedRowMutations = 0L,")

marker = "    private suspend fun prepareRoomBaseline(name: String, baseline: List<RefreshDeltaDatabaseItem>) {"
if text.count(marker) != 1:
    raise SystemExit("prepareRoomBaseline marker not unique")
insert = '''    private fun auditCandidateMutations(
        scenario: RefreshDeltaDatabaseScenario,
        baseline: List<RefreshDeltaDatabaseItem>,
        incoming: List<RefreshDeltaDatabaseItem>,
    ): Map<String, Long> {
        val name = nextDatabaseName("cow-audit", scenario.id, 0)
        cleanup(name)
        val databaseFile = applicationContext.getDatabasePath(name)
        databaseFile.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            check(database.enableWriteAheadLogging() || database.isWriteAheadLoggingEnabled)
            database.execSQL("PRAGMA foreign_keys=ON")
            createCandidateSchema(database)
            prepareCandidateBaseline(database, baseline)
            installCandidateAuditTriggers(database)
            checkpoint(database)

            candidateTransaction(database) {
                database.execSQL(
                    "INSERT INTO c03_b_revisions(source_id, revision_number, status) VALUES(?, ?, 'STAGING')",
                    arrayOf<Any?>(SOURCE_ID, REFRESH_REVISION),
                )
            }
            val statements = CandidateStatements(database)
            try {
                incoming.chunked(BATCH_SIZE).forEach { batch ->
                    candidateTransaction(database) {
                        batch.forEach { item -> statements.stage(item, REFRESH_REVISION) }
                    }
                }
            } finally {
                statements.close()
            }
            candidateTransaction(database) {
                database.execSQL(
                    "UPDATE c03_b_revisions SET status='RETAINED' WHERE source_id=? AND status='ACTIVE'",
                    arrayOf(SOURCE_ID),
                )
                database.execSQL(
                    "UPDATE c03_b_revisions SET status='ACTIVE' WHERE source_id=? AND revision_number=? AND status='STAGING'",
                    arrayOf<Any?>(SOURCE_ID, REFRESH_REVISION),
                )
                database.execSQL(
                    "UPDATE c03_b_sources SET retained_revision=active_revision, active_revision=? WHERE id=?",
                    arrayOf<Any?>(REFRESH_REVISION, SOURCE_ID),
                )
            }

            val counts = linkedMapOf<String, Long>()
            database.rawQuery(
                "SELECT table_name, operation, mutation_count FROM c03_b_audit_counts ORDER BY table_name, operation",
                emptyArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    counts["${cursor.getString(0)}.${cursor.getString(1)}"] = cursor.getLong(2)
                }
            }
            return counts.filterValues { it > 0L }
        } finally {
            database.close()
            cleanup(name)
        }
    }

    private fun installCandidateAuditTriggers(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE c03_b_audit_counts(
                table_name TEXT NOT NULL,
                operation TEXT NOT NULL,
                mutation_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(table_name, operation)
            )
            """.trimIndent(),
        )
        CANDIDATE_AUDITED_TABLES.forEach { table ->
            AUDITED_OPERATIONS.forEach { operation ->
                database.execSQL(
                    "INSERT INTO c03_b_audit_counts(table_name, operation, mutation_count) VALUES(?, ?, 0)",
                    arrayOf(table, operation),
                )
                val sqlOperation = operation.uppercase(Locale.ROOT)
                database.execSQL(
                    """
                    CREATE TRIGGER c03_b_audit_${table}_${operation}
                    AFTER $sqlOperation ON $table
                    BEGIN
                        UPDATE c03_b_audit_counts
                        SET mutation_count = mutation_count + 1
                        WHERE table_name = '$table' AND operation = '$operation';
                    END
                    """.trimIndent(),
                )
            }
        }
    }

'''
text = text.replace(marker, insert + marker)

constants = '        val AUDITED_OPERATIONS = listOf("insert", "update", "delete")'
replacement = '''        val CANDIDATE_AUDITED_TABLES = listOf(
            "c03_b_sources",
            "c03_b_revisions",
            "c03_b_canonical_channels",
            "c03_b_payloads",
            "c03_b_search_payloads",
            "c03_b_revision_entries",
        )
        val AUDITED_OPERATIONS = listOf("insert", "update", "delete")'''
if text.count(constants) != 1:
    raise SystemExit("AUDITED_OPERATIONS marker not unique")
text = text.replace(constants, replacement)

path.write_text(text)

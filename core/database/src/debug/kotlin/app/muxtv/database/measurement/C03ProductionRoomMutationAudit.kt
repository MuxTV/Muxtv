package app.muxtv.database.measurement

import android.database.sqlite.SQLiteDatabase

internal data class C03ProductionRoomTableMutationCount(
    val tableName: String,
    val inserts: Long,
    val updates: Long,
    val deletes: Long,
) {
    init {
        require(tableName.isNotBlank())
        require(inserts >= 0L)
        require(updates >= 0L)
        require(deletes >= 0L)
    }

    val total: Long get() = inserts + updates + deletes
}

internal data class C03ProductionRoomMutationAuditSnapshot(
    val tables: List<C03ProductionRoomTableMutationCount>,
) {
    fun table(tableName: String): C03ProductionRoomTableMutationCount =
        checkNotNull(tables.singleOrNull { it.tableName == tableName }) {
            "C03 mutation audit table is missing: $tableName"
        }
}

/**
 * Untimed SQLite row-mutation audit used by the C03 production Room evidence harness.
 *
 * Triggers and counters are persistent database objects on purpose: the measured Room database
 * opens its own connections after audit installation, so TEMP triggers would silently miss those
 * mutations. Audit databases are disposable and are never used for latency/WAL evidence.
 */
internal object C03ProductionRoomMutationAudit {
    private const val AUDIT_TABLE = "c03_production_measurement_mutation_audit"
    private const val TRIGGER_PREFIX = "c03_production_measurement_audit"
    private val safeIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val operations = listOf("insert", "update", "delete")

    fun install(
        database: SQLiteDatabase,
        tableNames: Set<String>,
    ) {
        require(tableNames.isNotEmpty())
        val orderedTables = tableNames.toList().sorted()
        orderedTables.forEach(::requireSafeIdentifier)
        orderedTables.forEach { tableName ->
            check(tableExists(database, tableName)) {
                "C03 mutation audit target table does not exist: $tableName"
            }
        }

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$AUDIT_TABLE`(
                table_name TEXT NOT NULL,
                operation TEXT NOT NULL,
                mutation_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(table_name, operation)
            )
            """.trimIndent(),
        )

        orderedTables.forEach { tableName ->
            operations.forEach { operation ->
                database.execSQL(
                    "INSERT OR REPLACE INTO `$AUDIT_TABLE`(table_name, operation, mutation_count) VALUES(?, ?, 0)",
                    arrayOf(tableName, operation),
                )
                val triggerName = triggerName(tableName, operation)
                database.execSQL("DROP TRIGGER IF EXISTS `$triggerName`")
                database.execSQL(
                    """
                    CREATE TRIGGER `$triggerName`
                    AFTER ${operation.uppercase()} ON `$tableName`
                    BEGIN
                        UPDATE `$AUDIT_TABLE`
                        SET mutation_count = mutation_count + 1
                        WHERE table_name = '$tableName' AND operation = '$operation';
                    END
                    """.trimIndent(),
                )
            }
        }
    }

    fun snapshot(database: SQLiteDatabase): C03ProductionRoomMutationAuditSnapshot {
        val byTable = linkedMapOf<String, LongArray>()
        database.rawQuery(
            "SELECT table_name, operation, mutation_count FROM `$AUDIT_TABLE` ORDER BY table_name, operation",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tableName = cursor.getString(0)
                val operation = cursor.getString(1)
                val count = cursor.getLong(2)
                val counts = byTable.getOrPut(tableName) { LongArray(3) }
                when (operation) {
                    "insert" -> counts[0] = count
                    "update" -> counts[1] = count
                    "delete" -> counts[2] = count
                    else -> error("Unknown C03 mutation audit operation: $operation")
                }
            }
        }
        return C03ProductionRoomMutationAuditSnapshot(
            tables = byTable.map { (tableName, counts) ->
                C03ProductionRoomTableMutationCount(
                    tableName = tableName,
                    inserts = counts[0],
                    updates = counts[1],
                    deletes = counts[2],
                )
            },
        )
    }

    fun uninstall(
        database: SQLiteDatabase,
        tableNames: Set<String>,
    ) {
        tableNames.toList().sorted().forEach { tableName ->
            requireSafeIdentifier(tableName)
            operations.forEach { operation ->
                database.execSQL("DROP TRIGGER IF EXISTS `${triggerName(tableName, operation)}`")
            }
        }
        database.execSQL("DROP TABLE IF EXISTS `$AUDIT_TABLE`")
    }

    private fun tableExists(database: SQLiteDatabase, tableName: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }

    private fun triggerName(tableName: String, operation: String): String =
        "${TRIGGER_PREFIX}_${tableName}_$operation"

    private fun requireSafeIdentifier(identifier: String) {
        require(safeIdentifier.matches(identifier)) {
            "Unsafe C03 mutation audit SQLite identifier."
        }
    }
}

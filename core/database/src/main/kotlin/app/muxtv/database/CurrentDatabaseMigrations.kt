package app.muxtv.database

import androidx.room3.migration.Migration

internal const val CURRENT_DATABASE_VERSION = 10

internal val CURRENT_DATABASE_MIGRATIONS: Array<Migration> = validateCurrentMigrationChain(
    migrations = listOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    ),
    currentVersion = CURRENT_DATABASE_VERSION,
).toTypedArray()

internal fun validateCurrentMigrationChain(
    migrations: List<Migration>,
    currentVersion: Int,
): List<Migration> {
    require(currentVersion > 1) {
        "Current database version must be greater than one."
    }
    require(migrations.isNotEmpty()) {
        "Current database migration chain must not be empty."
    }
    require(migrations.first().startVersion == 1) {
        "Current database migration chain must start at version 1."
    }

    val duplicateEdges = migrations
        .groupingBy { migration -> migration.startVersion to migration.endVersion }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    require(duplicateEdges.isEmpty()) {
        "Current database migration chain contains duplicate edges: $duplicateEdges."
    }

    migrations.forEach { migration ->
        require(migration.endVersion == migration.startVersion + 1) {
            "Current database migration must advance exactly one version: " +
                "${migration.startVersion}->${migration.endVersion}."
        }
    }

    migrations.zipWithNext().forEach { (current, next) ->
        require(current.endVersion == next.startVersion) {
            "Current database migration chain is not contiguous: " +
                "${current.startVersion}->${current.endVersion}, " +
                "${next.startVersion}->${next.endVersion}."
        }
    }

    require(migrations.last().endVersion == currentVersion) {
        "Current database migration chain ends at ${migrations.last().endVersion}, " +
            "but MuxTvDatabase is version $currentVersion."
    }

    return migrations
}

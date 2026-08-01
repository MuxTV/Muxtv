package app.muxtv.database

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val MIGRATION_5_6 = Migration(5, 6) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_refresh_policies` (
            `sourceId` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `intervalMinutes` INTEGER NOT NULL,
            `unmeteredOnly` INTEGER NOT NULL,
            `requiresCharging` INTEGER NOT NULL,
            `updatedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `epg_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_refresh_states` (
            `sourceId` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `runToken` TEXT,
            `startedAtEpochMillis` INTEGER,
            `completedAtEpochMillis` INTEGER,
            `lastSuccessRevision` INTEGER,
            `lastSuccessAtEpochMillis` INTEGER,
            `resultFamily` TEXT,
            `resultCode` TEXT,
            `httpStatus` INTEGER,
            PRIMARY KEY(`sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `epg_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_refresh_attempts` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `runToken` TEXT NOT NULL,
            `trigger` TEXT NOT NULL,
            `startedAtEpochMillis` INTEGER NOT NULL,
            `completedAtEpochMillis` INTEGER NOT NULL,
            `resultState` TEXT NOT NULL,
            `resultFamily` TEXT NOT NULL,
            `resultCode` TEXT,
            `revisionNumber` INTEGER,
            `channelCount` INTEGER,
            `programmeCount` INTEGER,
            `skippedProgrammeCount` INTEGER,
            `warningCount` INTEGER,
            `unresolvedTimeCount` INTEGER,
            `httpStatus` INTEGER,
            FOREIGN KEY(`sourceId`) REFERENCES `epg_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_refresh_attempts_sourceId` ON `epg_refresh_attempts` (`sourceId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_refresh_attempts_sourceId_startedAtEpochMillis` ON `epg_refresh_attempts` (`sourceId`, `startedAtEpochMillis`)",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_refresh_attempts_runToken` ON `epg_refresh_attempts` (`runToken`)",
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_refresh_http_validators` (
            `sourceId` TEXT NOT NULL,
            `etag` TEXT,
            `lastModified` TEXT,
            `updatedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `epg_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
}

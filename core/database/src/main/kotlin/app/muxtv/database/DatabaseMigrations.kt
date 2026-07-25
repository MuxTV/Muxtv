package app.muxtv.database

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `source_revisions` (
            `sourceId` TEXT NOT NULL,
            `revisionNumber` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `startedAtEpochMillis` INTEGER NOT NULL,
            `activatedAtEpochMillis` INTEGER,
            `parsedEntries` INTEGER NOT NULL,
            `skippedEntries` INTEGER NOT NULL,
            `warningCount` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`, `revisionNumber`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_source_revisions_sourceId` ON `source_revisions` (`sourceId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_source_revisions_status` ON `source_revisions` (`status`)",
    )

    connection.execSQL(
        "ALTER TABLE `provider_channels` ADD COLUMN `revisionNumber` INTEGER NOT NULL DEFAULT 0",
    )
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `tvgId` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `tvgName` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `logoUrl` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `groupTitle` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `channelNumber` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `catchupMode` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `catchupSource` TEXT")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `catchupDays` INTEGER")
    connection.execSQL("ALTER TABLE `provider_channels` ADD COLUMN `catchupCorrection` TEXT")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_provider_channels_sourceId_revisionNumber` ON `provider_channels` (`sourceId`, `revisionNumber`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_provider_channels_sourceId_providerKey_revisionNumber` ON `provider_channels` (`sourceId`, `providerKey`, `revisionNumber`)",
    )

    connection.execSQL("ALTER TABLE `stream_variants` ADD COLUMN `userAgent` TEXT")
    connection.execSQL("ALTER TABLE `stream_variants` ADD COLUMN `referrer` TEXT")
}

internal val MIGRATION_2_3 = Migration(2, 3) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `source_refresh_policies` (
            `sourceId` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `intervalMinutes` INTEGER NOT NULL,
            `unmeteredOnly` INTEGER NOT NULL,
            `requiresCharging` INTEGER NOT NULL,
            `updatedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `source_refresh_states` (
            `sourceId` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `runToken` TEXT,
            `startedAtEpochMillis` INTEGER,
            `completedAtEpochMillis` INTEGER,
            `lastSuccessRevision` INTEGER,
            `lastSuccessAtEpochMillis` INTEGER,
            `failureFamily` TEXT,
            `failureCode` TEXT,
            `httpStatus` INTEGER,
            `skippedEntries` INTEGER NOT NULL,
            `warningCount` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `source_refresh_attempts` (
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
            `parsedEntries` INTEGER,
            `skippedEntries` INTEGER NOT NULL,
            `warningCount` INTEGER NOT NULL,
            `httpStatus` INTEGER,
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_source_refresh_attempts_sourceId` ON `source_refresh_attempts` (`sourceId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_source_refresh_attempts_sourceId_startedAtEpochMillis` ON `source_refresh_attempts` (`sourceId`, `startedAtEpochMillis`)",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_source_refresh_attempts_runToken` ON `source_refresh_attempts` (`runToken`)",
    )
}

internal val MIGRATION_3_4 = Migration(3, 4) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `pending_source_preparations` (
            `preparationId` TEXT NOT NULL,
            `scheme` TEXT NOT NULL,
            `host` TEXT NOT NULL,
            `createdAtEpochMillis` INTEGER NOT NULL,
            `expiresAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`preparationId`)
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_pending_source_preparations_expiresAtEpochMillis` ON `pending_source_preparations` (`expiresAtEpochMillis`)",
    )
}

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

internal val MIGRATION_4_5 = Migration(4, 5) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_sources` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `providerSourceId` TEXT,
            `accessRef` TEXT,
            `defaultZoneId` TEXT,
            `activeRevision` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`providerSourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_sources_providerSourceId` ON `epg_sources` (`providerSourceId`)",
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_revisions` (
            `sourceId` TEXT NOT NULL,
            `revisionNumber` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `startedAtEpochMillis` INTEGER NOT NULL,
            `activatedAtEpochMillis` INTEGER,
            `acceptedChannels` INTEGER NOT NULL,
            `acceptedProgrammes` INTEGER NOT NULL,
            `skippedProgrammes` INTEGER NOT NULL,
            `warningCount` INTEGER NOT NULL,
            `unresolvedTimeCount` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`, `revisionNumber`),
            FOREIGN KEY(`sourceId`) REFERENCES `epg_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_revisions_sourceId_status` ON `epg_revisions` (`sourceId`, `status`)",
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_channels` (
            `sourceId` TEXT NOT NULL,
            `revisionNumber` INTEGER NOT NULL,
            `externalId` TEXT NOT NULL,
            `primaryDisplayName` TEXT,
            `primaryLanguage` TEXT,
            `iconRef` TEXT,
            PRIMARY KEY(`sourceId`, `revisionNumber`, `externalId`),
            FOREIGN KEY(`sourceId`, `revisionNumber`) REFERENCES `epg_revisions`(`sourceId`, `revisionNumber`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_channels_sourceId_revisionNumber` ON `epg_channels` (`sourceId`, `revisionNumber`)",
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_programmes` (
            `sourceId` TEXT NOT NULL,
            `revisionNumber` INTEGER NOT NULL,
            `sequenceNumber` INTEGER NOT NULL,
            `externalChannelId` TEXT NOT NULL,
            `startEpochMillis` INTEGER NOT NULL,
            `stopEpochMillis` INTEGER,
            `primaryTitle` TEXT,
            `primaryLanguage` TEXT,
            `subtitle` TEXT,
            `description` TEXT,
            `category` TEXT,
            `iconRef` TEXT,
            `episodeNumber` TEXT,
            `isNew` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`, `revisionNumber`, `sequenceNumber`),
            FOREIGN KEY(`sourceId`, `revisionNumber`) REFERENCES `epg_revisions`(`sourceId`, `revisionNumber`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_revisionNumber_externalChannelId_startEpochMillis` ON `epg_programmes` (`sourceId`, `revisionNumber`, `externalChannelId`, `startEpochMillis`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_programmes_sourceId_revisionNumber_startEpochMillis_stopEpochMillis` ON `epg_programmes` (`sourceId`, `revisionNumber`, `startEpochMillis`, `stopEpochMillis`)",
    )
}

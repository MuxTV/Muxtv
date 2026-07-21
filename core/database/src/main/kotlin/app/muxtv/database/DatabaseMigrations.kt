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

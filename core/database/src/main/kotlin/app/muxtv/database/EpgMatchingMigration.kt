package app.muxtv.database

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val MIGRATION_6_7 = Migration(6, 7) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `epg_channel_matches` (
            `epgSourceId` TEXT NOT NULL,
            `epgRevisionNumber` INTEGER NOT NULL,
            `providerSourceId` TEXT NOT NULL,
            `catalogRevisionNumber` INTEGER NOT NULL,
            `epgExternalChannelId` TEXT NOT NULL,
            `decision` TEXT NOT NULL,
            `reasonCode` TEXT NOT NULL,
            `canonicalChannelId` TEXT,
            `candidateCount` INTEGER NOT NULL,
            PRIMARY KEY(
                `epgSourceId`,
                `epgRevisionNumber`,
                `providerSourceId`,
                `catalogRevisionNumber`,
                `epgExternalChannelId`
            ),
            FOREIGN KEY(
                `epgSourceId`, `epgRevisionNumber`, `epgExternalChannelId`
            ) REFERENCES `epg_channels`(
                `sourceId`, `revisionNumber`, `externalId`
            ) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(
                `providerSourceId`, `catalogRevisionNumber`
            ) REFERENCES `source_revisions`(
                `sourceId`, `revisionNumber`
            ) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`canonicalChannelId`) REFERENCES `canonical_channels`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_channel_matches_epgSourceId_epgRevisionNumber_epgExternalChannelId` " +
            "ON `epg_channel_matches` (`epgSourceId`, `epgRevisionNumber`, `epgExternalChannelId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_channel_matches_providerSourceId_catalogRevisionNumber` " +
            "ON `epg_channel_matches` (`providerSourceId`, `catalogRevisionNumber`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_epg_channel_matches_canonicalChannelId` " +
            "ON `epg_channel_matches` (`canonicalChannelId`)",
    )
}

/**
 * Rows from v7 have no matching-policy provenance. They are deliberately marked policy 0 so guide
 * readers ignore them and [EpgMatchingStore.reconcileIfStale] rebuilds them under the current policy.
 */
internal val MIGRATION_7_8 = Migration(7, 8) { connection ->
    connection.execSQL(
        "ALTER TABLE `epg_channel_matches` " +
            "ADD COLUMN `matchPolicyVersion` INTEGER NOT NULL DEFAULT 0",
    )
}

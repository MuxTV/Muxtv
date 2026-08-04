package app.muxtv.database

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val MIGRATION_9_10 = Migration(9, 10) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `recent_channels` (
            `profileId` TEXT NOT NULL,
            `canonicalChannelId` TEXT NOT NULL,
            `lastSuccessfulPlaybackAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`profileId`, `canonicalChannelId`),
            FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`canonicalChannelId`) REFERENCES `canonical_channels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_recent_channels_canonicalChannelId` " +
            "ON `recent_channels` (`canonicalChannelId`)",
    )
}

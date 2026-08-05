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
            FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
}

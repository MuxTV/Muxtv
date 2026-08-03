package app.muxtv.database

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

internal val MIGRATION_8_9 = Migration(8, 9) { connection ->
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `search_documents` (
            `rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `documentKey` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `canonicalChannelId` TEXT,
            `profileId` TEXT,
            `providerChannelId` TEXT,
            `epgSourceId` TEXT,
            `epgRevisionNumber` INTEGER,
            `epgExternalChannelId` TEXT,
            `epgProgrammeSequence` INTEGER,
            `text` TEXT NOT NULL
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_documents_documentKey` " +
            "ON `search_documents` (`documentKey`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_search_documents_canonicalChannelId` " +
            "ON `search_documents` (`canonicalChannelId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_search_documents_profileId_canonicalChannelId` " +
            "ON `search_documents` (`profileId`, `canonicalChannelId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_search_documents_providerChannelId` " +
            "ON `search_documents` (`providerChannelId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_search_documents_epgSourceId_epgRevisionNumber_epgExternalChannelId_epgProgrammeSequence` " +
            "ON `search_documents` (`epgSourceId`, `epgRevisionNumber`, `epgExternalChannelId`, `epgProgrammeSequence`)",
    )
    connection.execSQL(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS `search_documents_fts`
        USING FTS4(`text`, content=`search_documents`, tokenize=unicode61)
        """.trimIndent(),
    )

    connection.execSQL(
        """
        INSERT INTO search_documents(documentKey, kind, canonicalChannelId, text)
        SELECT 'canonical-name:' || id,
               '${SearchDocumentKind.CANONICAL_NAME}',
               id,
               displayName
        FROM canonical_channels
        WHERE TRIM(displayName) <> ''
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO search_documents(
            documentKey, kind, canonicalChannelId, providerChannelId, text
        )
        SELECT DISTINCT
               'provider-raw:' || provider_channels.id || ':' || stream_variants.canonicalChannelId,
               '${SearchDocumentKind.PROVIDER_RAW_NAME}',
               stream_variants.canonicalChannelId,
               provider_channels.id,
               provider_channels.rawName
        FROM provider_channels
        INNER JOIN stream_variants
            ON stream_variants.providerChannelId = provider_channels.id
        WHERE TRIM(provider_channels.rawName) <> ''
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO search_documents(
            documentKey, kind, canonicalChannelId, providerChannelId, text
        )
        SELECT DISTINCT
               'provider-group:' || provider_channels.id || ':' || stream_variants.canonicalChannelId,
               '${SearchDocumentKind.PROVIDER_GROUP}',
               stream_variants.canonicalChannelId,
               provider_channels.id,
               provider_channels.groupTitle
        FROM provider_channels
        INNER JOIN stream_variants
            ON stream_variants.providerChannelId = provider_channels.id
        WHERE provider_channels.groupTitle IS NOT NULL
          AND TRIM(provider_channels.groupTitle) <> ''
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO search_documents(
            documentKey, kind, canonicalChannelId, providerChannelId, text
        )
        SELECT DISTINCT
               'provider-number:' || provider_channels.id || ':' || stream_variants.canonicalChannelId,
               '${SearchDocumentKind.PROVIDER_NUMBER}',
               stream_variants.canonicalChannelId,
               provider_channels.id,
               provider_channels.channelNumber
        FROM provider_channels
        INNER JOIN stream_variants
            ON stream_variants.providerChannelId = provider_channels.id
        WHERE provider_channels.channelNumber IS NOT NULL
          AND TRIM(provider_channels.channelNumber) <> ''
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO search_documents(
            documentKey, kind, canonicalChannelId, profileId, text
        )
        SELECT 'overlay-name:' || profileId || ':' || canonicalChannelId,
               '${SearchDocumentKind.OVERLAY_CUSTOM_NAME}',
               canonicalChannelId,
               profileId,
               customName
        FROM user_channel_overlays
        WHERE customName IS NOT NULL
          AND TRIM(customName) <> ''
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO search_documents(
            documentKey, kind, canonicalChannelId, profileId, text
        )
        SELECT 'overlay-number:' || profileId || ':' || canonicalChannelId,
               '${SearchDocumentKind.OVERLAY_NUMBER}',
               canonicalChannelId,
               profileId,
               CAST(channelNumber AS TEXT)
        FROM user_channel_overlays
        WHERE channelNumber IS NOT NULL
        """.trimIndent(),
    )
    connection.execSQL(
        """
        INSERT INTO search_documents(
            documentKey,
            kind,
            epgSourceId,
            epgRevisionNumber,
            epgExternalChannelId,
            epgProgrammeSequence,
            text
        )
        SELECT 'epg-title:' || sourceId || ':' || revisionNumber || ':' || sequenceNumber,
               '${SearchDocumentKind.EPG_PROGRAMME_TITLE}',
               sourceId,
               revisionNumber,
               externalChannelId,
               sequenceNumber,
               primaryTitle
        FROM epg_programmes
        WHERE primaryTitle IS NOT NULL
          AND TRIM(primaryTitle) <> ''
        """.trimIndent(),
    )

    // Room removes external-content synchronization triggers while migrations run. Rebuild the
    // FTS index explicitly from the backfilled content table; Room recreates sync triggers after
    // migration validation/open completes.
    connection.execSQL(
        "INSERT INTO search_documents_fts(search_documents_fts) VALUES('rebuild')",
    )
}

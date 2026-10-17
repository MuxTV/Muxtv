package app.muxtv.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.muxtv.common.catalog.CatalogFingerprintCodec
import app.muxtv.common.catalog.CatalogPayloadFingerprintInput

internal val MIGRATION_10_11 = Migration(10, 11) { connection ->
    createC03ProductionTables(connection)
    backfillC03ProductionTables(connection)
}

private fun createC03ProductionTables(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `catalog_search_payloads` (
            `searchPayloadId` TEXT NOT NULL,
            `searchContentHash` TEXT NOT NULL,
            `searchHashVersion` INTEGER NOT NULL,
            `canonicalChannelId` TEXT NOT NULL,
            `rawName` TEXT NOT NULL,
            `groupTitle` TEXT,
            `channelNumber` TEXT,
            PRIMARY KEY(`searchPayloadId`)
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_search_payloads_searchContentHash` " +
            "ON `catalog_search_payloads` (`searchContentHash`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_search_payloads_canonicalChannelId` " +
            "ON `catalog_search_payloads` (`canonicalChannelId`)",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_catalog_search_payloads_searchHashVersion_searchContentHash` " +
            "ON `catalog_search_payloads` (`searchHashVersion`, `searchContentHash`)",
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `catalog_payloads` (
            `payloadId` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `logicalChannelId` TEXT NOT NULL,
            `contentHash` TEXT NOT NULL,
            `contentHashVersion` INTEGER NOT NULL,
            `canonicalChannelId` TEXT NOT NULL,
            `providerKey` TEXT NOT NULL,
            `rawName` TEXT NOT NULL,
            `tvgId` TEXT,
            `tvgName` TEXT,
            `logoUrl` TEXT,
            `groupTitle` TEXT,
            `channelNumber` TEXT,
            `catchupMode` TEXT,
            `catchupSource` TEXT,
            `catchupDays` INTEGER,
            `catchupCorrection` TEXT,
            `locator` TEXT NOT NULL,
            `userAgent` TEXT,
            `referrer` TEXT,
            `searchPayloadId` TEXT NOT NULL,
            PRIMARY KEY(`payloadId`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`searchPayloadId`) REFERENCES `catalog_search_payloads`(`searchPayloadId`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`canonicalChannelId`) REFERENCES `canonical_channels`(`id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_payloads_sourceId` " +
            "ON `catalog_payloads` (`sourceId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_payloads_searchPayloadId` " +
            "ON `catalog_payloads` (`searchPayloadId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_payloads_canonicalChannelId` " +
            "ON `catalog_payloads` (`canonicalChannelId`)",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_catalog_payloads_sourceId_logicalChannelId_contentHashVersion_contentHash` " +
            "ON `catalog_payloads` " +
            "(`sourceId`, `logicalChannelId`, `contentHashVersion`, `contentHash`)",
    )

    connection.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `source_revision_memberships` (
            `sourceId` TEXT NOT NULL,
            `revisionNumber` INTEGER NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `logicalChannelId` TEXT NOT NULL,
            `payloadId` TEXT NOT NULL,
            `variantId` TEXT NOT NULL,
            PRIMARY KEY(`sourceId`, `revisionNumber`, `ordinal`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`payloadId`) REFERENCES `catalog_payloads`(`payloadId`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_source_revision_memberships_sourceId_revisionNumber` " +
            "ON `source_revision_memberships` (`sourceId`, `revisionNumber`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_source_revision_memberships_sourceId_revisionNumber_logicalChannelId` " +
            "ON `source_revision_memberships` " +
            "(`sourceId`, `revisionNumber`, `logicalChannelId`)",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_source_revision_memberships_payloadId` " +
            "ON `source_revision_memberships` (`payloadId`)",
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_source_revision_memberships_variantId` " +
            "ON `source_revision_memberships` (`variantId`)",
    )
}

private fun backfillC03ProductionTables(connection: SQLiteConnection) {
    val codec = CatalogFingerprintCodec()
    val select = connection.prepare(
        """
        SELECT p.sourceId,
               p.revisionNumber,
               p.providerKey,
               p.rawName,
               p.tvgId,
               p.tvgName,
               p.logoUrl,
               p.groupTitle,
               p.channelNumber,
               p.catchupMode,
               p.catchupSource,
               p.catchupDays,
               p.catchupCorrection,
               v.id,
               v.canonicalChannelId,
               v.locator,
               v.userAgent,
               v.referrer
        FROM provider_channels AS p
        INNER JOIN stream_variants AS v
            ON v.providerChannelId = p.id
        ORDER BY p.sourceId COLLATE BINARY,
                 p.revisionNumber,
                 p.id COLLATE BINARY,
                 v.id COLLATE BINARY
        """.trimIndent(),
    )
    val insertSearchPayload = connection.prepare(
        """
        INSERT OR IGNORE INTO catalog_search_payloads(
            searchPayloadId,
            searchContentHash,
            searchHashVersion,
            canonicalChannelId,
            rawName,
            groupTitle,
            channelNumber
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    val insertPayload = connection.prepare(
        """
        INSERT OR IGNORE INTO catalog_payloads(
            payloadId,
            sourceId,
            logicalChannelId,
            contentHash,
            contentHashVersion,
            canonicalChannelId,
            providerKey,
            rawName,
            tvgId,
            tvgName,
            logoUrl,
            groupTitle,
            channelNumber,
            catchupMode,
            catchupSource,
            catchupDays,
            catchupCorrection,
            locator,
            userAgent,
            referrer,
            searchPayloadId
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    val insertMembership = connection.prepare(
        """
        INSERT INTO source_revision_memberships(
            sourceId,
            revisionNumber,
            ordinal,
            logicalChannelId,
            payloadId,
            variantId
        ) VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    )
    val verifySearchPayload = connection.prepare(
        """
        SELECT searchContentHash,
               searchHashVersion,
               canonicalChannelId,
               rawName,
               groupTitle,
               channelNumber
        FROM catalog_search_payloads
        WHERE searchPayloadId = ?
        LIMIT 1
        """.trimIndent(),
    )
    val verifyPayload = connection.prepare(
        """
        SELECT sourceId,
               logicalChannelId,
               contentHash,
               contentHashVersion,
               canonicalChannelId,
               providerKey,
               rawName,
               tvgId,
               tvgName,
               logoUrl,
               groupTitle,
               channelNumber,
               catchupMode,
               catchupSource,
               catchupDays,
               catchupCorrection,
               locator,
               userAgent,
               referrer,
               searchPayloadId
        FROM catalog_payloads
        WHERE payloadId = ?
        LIMIT 1
        """.trimIndent(),
    )

    select.use { rows ->
        insertSearchPayload.use { searchStatement ->
            insertPayload.use { payloadStatement ->
                insertMembership.use { membershipStatement ->
                    verifySearchPayload.use { verifySearchStatement ->
                        verifyPayload.use { verifyPayloadStatement ->
                    var currentSourceId: String? = null
                    var currentRevision = Long.MIN_VALUE
                    var ordinal = 0L

                    while (rows.step()) {
                        val sourceId = rows.getText(0)
                        val revisionNumber = rows.getLong(1)
                        if (sourceId != currentSourceId || revisionNumber != currentRevision) {
                            currentSourceId = sourceId
                            currentRevision = revisionNumber
                            ordinal = 0L
                        }
                        ordinal += 1

                        val providerKey = rows.getText(2)
                        val rawName = rows.getText(3)
                        val tvgId = rows.nullableText(4)
                        val tvgName = rows.nullableText(5)
                        val logoUrl = rows.nullableText(6)
                        val groupTitle = rows.nullableText(7)
                        val channelNumber = rows.nullableText(8)
                        val catchupMode = rows.nullableText(9)
                        val catchupSource = rows.nullableText(10)
                        val catchupDays = rows.nullableLong(11)?.toInt()
                        val catchupCorrection = rows.nullableText(12)
                        val variantId = rows.getText(13)
                        val canonicalChannelId = rows.getText(14)
                        val locator = rows.getText(15)
                        val userAgent = rows.nullableText(16)
                        val referrer = rows.nullableText(17)

                        val logicalChannelId = codec.logicalChannelId(sourceId, providerKey)
                        val fingerprintInput = CatalogPayloadFingerprintInput(
                            providerKey = providerKey,
                            canonicalChannelId = canonicalChannelId,
                            rawName = rawName,
                            tvgId = tvgId,
                            tvgName = tvgName,
                            logoUrl = logoUrl,
                            groupTitle = groupTitle,
                            channelNumber = channelNumber,
                            catchupMode = catchupMode,
                            catchupSource = catchupSource,
                            catchupDays = catchupDays,
                            catchupCorrection = catchupCorrection,
                            locator = locator,
                            userAgent = userAgent,
                            referrer = referrer,
                        )
                        val fingerprint = codec.fingerprint(fingerprintInput)
                        val searchPayloadId = codec.searchPayloadId(
                            searchHashVersion = fingerprint.searchHashVersion,
                            searchContentHash = fingerprint.searchContentHash,
                        )
                        val payloadId = codec.payloadId(
                            sourceId = sourceId,
                            logicalChannelId = logicalChannelId,
                            contentHashVersion = fingerprint.contentHashVersion,
                            contentHash = fingerprint.contentHash,
                        )

                        val searchPayload = CatalogSearchPayloadEntity(
                            searchPayloadId = searchPayloadId,
                            searchContentHash = fingerprint.searchContentHash,
                            searchHashVersion = fingerprint.searchHashVersion,
                            canonicalChannelId = canonicalChannelId,
                            rawName = rawName,
                            groupTitle = groupTitle,
                            channelNumber = channelNumber,
                        )
                        bindSearchPayload(searchStatement, searchPayload)
                        searchStatement.step()
                        searchStatement.reset()
                        searchStatement.clearBindings()
                        verifySearchPayload(verifySearchStatement, searchPayload)

                        val payload = CatalogPayloadEntity(
                            payloadId = payloadId,
                            sourceId = sourceId,
                            logicalChannelId = logicalChannelId,
                            contentHash = fingerprint.contentHash,
                            contentHashVersion = fingerprint.contentHashVersion,
                            canonicalChannelId = canonicalChannelId,
                            providerKey = providerKey,
                            rawName = rawName,
                            tvgId = tvgId,
                            tvgName = tvgName,
                            logoUrl = logoUrl,
                            groupTitle = groupTitle,
                            channelNumber = channelNumber,
                            catchupMode = catchupMode,
                            catchupSource = catchupSource,
                            catchupDays = catchupDays,
                            catchupCorrection = catchupCorrection,
                            locator = locator,
                            userAgent = userAgent,
                            referrer = referrer,
                            searchPayloadId = searchPayloadId,
                        )
                        bindPayload(payloadStatement, payload)
                        payloadStatement.step()
                        payloadStatement.reset()
                        payloadStatement.clearBindings()
                        verifyPayload(verifyPayloadStatement, payload)

                        membershipStatement.bindText(1, sourceId)
                        membershipStatement.bindLong(2, revisionNumber)
                        membershipStatement.bindLong(3, ordinal)
                        membershipStatement.bindText(4, logicalChannelId)
                        membershipStatement.bindText(5, payloadId)
                        membershipStatement.bindText(6, variantId)
                        membershipStatement.step()
                        membershipStatement.reset()
                        membershipStatement.clearBindings()
                    }
                        }
                    }
                }
            }
        }
    }
}

private fun bindSearchPayload(
    statement: SQLiteStatement,
    entity: CatalogSearchPayloadEntity,
) {
    statement.bindText(1, entity.searchPayloadId)
    statement.bindText(2, entity.searchContentHash)
    statement.bindLong(3, entity.searchHashVersion.toLong())
    statement.bindText(4, entity.canonicalChannelId)
    statement.bindText(5, entity.rawName)
    statement.bindNullableText(6, entity.groupTitle)
    statement.bindNullableText(7, entity.channelNumber)
}

private fun bindPayload(
    statement: SQLiteStatement,
    entity: CatalogPayloadEntity,
) {
    statement.bindText(1, entity.payloadId)
    statement.bindText(2, entity.sourceId)
    statement.bindText(3, entity.logicalChannelId)
    statement.bindText(4, entity.contentHash)
    statement.bindLong(5, entity.contentHashVersion.toLong())
    statement.bindText(6, entity.canonicalChannelId)
    statement.bindText(7, entity.providerKey)
    statement.bindText(8, entity.rawName)
    statement.bindNullableText(9, entity.tvgId)
    statement.bindNullableText(10, entity.tvgName)
    statement.bindNullableText(11, entity.logoUrl)
    statement.bindNullableText(12, entity.groupTitle)
    statement.bindNullableText(13, entity.channelNumber)
    statement.bindNullableText(14, entity.catchupMode)
    statement.bindNullableText(15, entity.catchupSource)
    statement.bindNullableLong(16, entity.catchupDays?.toLong())
    statement.bindNullableText(17, entity.catchupCorrection)
    statement.bindText(18, entity.locator)
    statement.bindNullableText(19, entity.userAgent)
    statement.bindNullableText(20, entity.referrer)
    statement.bindText(21, entity.searchPayloadId)
}

private fun verifySearchPayload(
    statement: SQLiteStatement,
    expected: CatalogSearchPayloadEntity,
) {
    statement.bindText(1, expected.searchPayloadId)
    check(statement.step()) { "Missing migrated immutable search payload." }
    check(statement.getText(0) == expected.searchContentHash)
    check(statement.getLong(1).toInt() == expected.searchHashVersion)
    check(statement.getText(2) == expected.canonicalChannelId)
    check(statement.getText(3) == expected.rawName)
    check(statement.nullableText(4) == expected.groupTitle)
    check(statement.nullableText(5) == expected.channelNumber)
    check(!statement.step())
    statement.reset()
    statement.clearBindings()
}

private fun verifyPayload(
    statement: SQLiteStatement,
    expected: CatalogPayloadEntity,
) {
    statement.bindText(1, expected.payloadId)
    check(statement.step()) { "Missing migrated immutable catalog payload." }
    check(statement.getText(0) == expected.sourceId)
    check(statement.getText(1) == expected.logicalChannelId)
    check(statement.getText(2) == expected.contentHash)
    check(statement.getLong(3).toInt() == expected.contentHashVersion)
    check(statement.getText(4) == expected.canonicalChannelId)
    check(statement.getText(5) == expected.providerKey)
    check(statement.getText(6) == expected.rawName)
    check(statement.nullableText(7) == expected.tvgId)
    check(statement.nullableText(8) == expected.tvgName)
    check(statement.nullableText(9) == expected.logoUrl)
    check(statement.nullableText(10) == expected.groupTitle)
    check(statement.nullableText(11) == expected.channelNumber)
    check(statement.nullableText(12) == expected.catchupMode)
    check(statement.nullableText(13) == expected.catchupSource)
    check(statement.nullableLong(14)?.toInt() == expected.catchupDays)
    check(statement.nullableText(15) == expected.catchupCorrection)
    check(statement.getText(16) == expected.locator)
    check(statement.nullableText(17) == expected.userAgent)
    check(statement.nullableText(18) == expected.referrer)
    check(statement.getText(19) == expected.searchPayloadId)
    check(!statement.step())
    statement.reset()
    statement.clearBindings()
}

private fun SQLiteStatement.bindNullableText(
    index: Int,
    value: String?,
) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.bindNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun SQLiteStatement.nullableText(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun SQLiteStatement.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

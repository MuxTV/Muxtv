package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "catalog_search_payloads",
    indices = [
        Index(value = ["searchContentHash"]),
        Index(value = ["canonicalChannelId"]),
        Index(value = ["searchHashVersion", "searchContentHash"], unique = true),
    ],
)
internal data class CatalogSearchPayloadEntity(
    @PrimaryKey val searchPayloadId: String,
    val searchContentHash: String,
    val searchHashVersion: Int,
    val canonicalChannelId: String,
    val rawName: String,
    val groupTitle: String?,
    val channelNumber: String?,
) {
    override fun toString(): String =
        "CatalogSearchPayloadEntity(searchPayloadId=<redacted>, searchHashVersion=$searchHashVersion, " +
            "canonicalChannelId=<redacted>, rawName=<redacted>, groupTitle=<redacted>, " +
            "channelNumber=<redacted>)"
}

@Entity(
    tableName = "catalog_payloads",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CatalogSearchPayloadEntity::class,
            parentColumns = ["searchPayloadId"],
            childColumns = ["searchPayloadId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["searchPayloadId"]),
        Index(value = ["canonicalChannelId"]),
        Index(
            value = ["sourceId", "logicalChannelId", "contentHashVersion", "contentHash"],
            unique = true,
        ),
    ],
)
internal data class CatalogPayloadEntity(
    @PrimaryKey val payloadId: String,
    val sourceId: String,
    val logicalChannelId: String,
    val contentHash: String,
    val contentHashVersion: Int,
    val canonicalChannelId: String,
    val providerKey: String,
    val rawName: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
    val locator: String,
    val userAgent: String?,
    val referrer: String?,
    val searchPayloadId: String,
) {
    override fun toString(): String =
        "CatalogPayloadEntity(payloadId=<redacted>, sourceId=<redacted>, " +
            "logicalChannelId=<redacted>, contentHashVersion=$contentHashVersion, " +
            "canonicalChannelId=<redacted>, providerKey=<redacted>, rawName=<redacted>, " +
            "locator=<redacted>, catchupSource=<redacted>, userAgentPresent=${userAgent != null}, " +
            "referrerPresent=${referrer != null}, searchPayloadId=<redacted>)"
}

@Entity(
    tableName = "source_revision_memberships",
    primaryKeys = ["sourceId", "revisionNumber", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CatalogPayloadEntity::class,
            parentColumns = ["payloadId"],
            childColumns = ["payloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "revisionNumber"]),
        Index(value = ["sourceId", "revisionNumber", "logicalChannelId"]),
        Index(value = ["payloadId"]),
        Index(value = ["variantId"], unique = true),
    ],
)
internal data class SourceRevisionMembershipEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val ordinal: Long,
    val logicalChannelId: String,
    val payloadId: String,
    val variantId: String,
) {
    init {
        require(sourceId.isNotBlank())
        require(revisionNumber >= 0)
        require(ordinal > 0)
        require(logicalChannelId.isNotBlank())
        require(payloadId.isNotBlank())
        require(variantId.isNotBlank())
    }
}

internal data class SourceRevisionMembershipDraft(
    val logicalChannelId: String,
    val payloadId: String,
    val variantId: String,
) {
    init {
        require(logicalChannelId.isNotBlank())
        require(payloadId.isNotBlank())
        require(variantId.isNotBlank())
    }
}


internal data class CatalogPayloadCompactionResult(
    val payloadRowsDeleted: Int,
    val searchPayloadRowsDeleted: Int,
) {
    val totalRowsDeleted: Int
        get() = payloadRowsDeleted + searchPayloadRowsDeleted
}

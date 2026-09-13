package app.muxtv.database.measurement

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "c03_production_candidate_sources")
data class C03ProductionCandidateSourceEntity(
    @PrimaryKey val sourceId: String,
    val credentialRef: String?,
    val activeRevision: Long = 0,
)

@Entity(
    tableName = "c03_production_candidate_revisions",
    primaryKeys = ["sourceId", "revisionNumber"],
    foreignKeys = [
        ForeignKey(
            entity = C03ProductionCandidateSourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["status"]),
    ],
)
data class C03ProductionCandidateRevisionEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val status: String,
    val startedAtEpochMillis: Long,
    val activatedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "c03_production_candidate_refresh_owners",
    foreignKeys = [
        ForeignKey(
            entity = C03ProductionCandidateSourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class C03ProductionCandidateRefreshOwnerEntity(
    @PrimaryKey val sourceId: String,
    val state: String,
    val runToken: String,
)

@Entity(
    tableName = "c03_production_candidate_search_payloads",
    indices = [
        Index(value = ["searchContentHash"]),
        Index(value = ["canonicalChannelId"]),
    ],
)
data class C03ProductionCandidateSearchPayloadEntity(
    @PrimaryKey val searchPayloadId: String,
    val searchContentHash: String,
    val searchHashVersion: Int,
    val canonicalChannelId: String,
    val rawName: String,
    val groupTitle: String?,
    val channelNumber: String?,
)

@Entity(
    tableName = "c03_production_candidate_search_documents",
    foreignKeys = [
        ForeignKey(
            entity = C03ProductionCandidateSearchPayloadEntity::class,
            parentColumns = ["searchPayloadId"],
            childColumns = ["searchPayloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["documentKey"], unique = true),
        Index(value = ["searchPayloadId"]),
    ],
)
data class C03ProductionCandidateSearchDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    val documentKey: String,
    val searchPayloadId: String,
    val kind: String,
    val text: String,
)

@Entity(tableName = "c03_production_candidate_search_documents_fts")
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    contentEntity = C03ProductionCandidateSearchDocumentEntity::class,
)
data class C03ProductionCandidateSearchDocumentFtsEntity(
    val text: String,
)

@Entity(
    tableName = "c03_production_candidate_payloads",
    foreignKeys = [
        ForeignKey(
            entity = C03ProductionCandidateSourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = C03ProductionCandidateSearchPayloadEntity::class,
            parentColumns = ["searchPayloadId"],
            childColumns = ["searchPayloadId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["searchPayloadId"]),
        Index(value = ["sourceId", "logicalChannelId", "contentHash"], unique = true),
    ],
)
data class C03ProductionCandidatePayloadEntity(
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
    val locator: String,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
    val userAgent: String?,
    val referrer: String?,
    val searchPayloadId: String,
)

@Entity(
    tableName = "c03_production_candidate_memberships",
    primaryKeys = ["sourceId", "revisionNumber", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = C03ProductionCandidateRevisionEntity::class,
            parentColumns = ["sourceId", "revisionNumber"],
            childColumns = ["sourceId", "revisionNumber"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = C03ProductionCandidatePayloadEntity::class,
            parentColumns = ["payloadId"],
            childColumns = ["payloadId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "revisionNumber"]),
        Index(value = ["sourceId", "revisionNumber", "logicalChannelId"]),
        Index(value = ["payloadId"]),
    ],
)
data class C03ProductionCandidateMembershipEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val ordinal: Long,
    val logicalChannelId: String,
    val payloadId: String,
)

data class C03ProductionCandidateStageEntry(
    val ordinal: Long,
    val logicalChannelId: String,
    val contentHash: String,
    val searchContentHash: String,
    val providerKey: String,
    val rawName: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val locator: String,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
    val userAgent: String?,
    val referrer: String?,
    val searchText: String,
    val canonicalChannelId: String = logicalChannelId,
    val contentHashVersion: Int = 1,
    val searchHashVersion: Int = 1,
) {
    init {
        require(ordinal >= 0)
        require(logicalChannelId.isNotBlank())
        require(contentHash.isNotBlank())
        require(searchContentHash.isNotBlank())
        require(providerKey.isNotBlank())
        require(rawName.isNotBlank())
        require(locator.isNotBlank())
        require(searchText.isNotBlank())
        require(canonicalChannelId.isNotBlank())
        require(contentHashVersion > 0)
        require(searchHashVersion > 0)
        require(catchupDays == null || catchupDays >= 0)
    }
}

data class C03ProductionCandidateRowCounts(
    val payloadRows: Int,
    val searchPayloadRows: Int,
    val membershipRows: Int,
    val searchDocumentRows: Int,
)

data class C03ProductionCandidateActiveRow(
    val ordinal: Long,
    val logicalChannelId: String,
    val payloadId: String,
    val contentHash: String,
    val canonicalChannelId: String,
    val rawName: String,
    val groupTitle: String?,
    val channelNumber: String?,
)

data class C03ProductionCandidateSearchRow(
    val ordinal: Long,
    val logicalChannelId: String,
    val payloadId: String,
    val canonicalChannelId: String,
    val rawName: String,
)

data class C03ProductionCandidateCompactionResult(
    val payloadRowsDeleted: Int,
    val searchPayloadRowsDeleted: Int,
)

enum class C03ProductionCandidateActivationResult {
    Published,
    EmptyRevisionRejected,
    Superseded,
}

internal object C03ProductionCandidateRevisionStatus {
    const val STAGING = "STAGING"
    const val ACTIVE = "ACTIVE"
    const val RETAINED = "RETAINED"
}

internal object C03ProductionCandidateRefreshState {
    const val RUNNING = "RUNNING"
}

internal object C03ProductionCandidateSearchKind {
    const val RAW_NAME = "RAW_NAME"
    const val GROUP = "GROUP"
    const val NUMBER = "NUMBER"
}

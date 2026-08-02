package app.muxtv.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

enum class EpgChannelMatchDecision {
    MATCHED,
    UNRESOLVED,
    AMBIGUOUS,
}

@Entity(
    tableName = "epg_channel_matches",
    foreignKeys = [
        ForeignKey(
            entity = EpgChannelEntity::class,
            parentColumns = ["sourceId", "revisionNumber", "externalId"],
            childColumns = ["epgSourceId", "epgRevisionNumber", "epgExternalChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceRevisionEntity::class,
            parentColumns = ["sourceId", "revisionNumber"],
            childColumns = ["providerSourceId", "catalogRevisionNumber"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CanonicalChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonicalChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["epgSourceId", "epgRevisionNumber", "epgExternalChannelId"]),
        Index(value = ["providerSourceId", "catalogRevisionNumber"]),
        Index(value = ["canonicalChannelId"]),
    ],
    primaryKeys = [
        "epgSourceId",
        "epgRevisionNumber",
        "providerSourceId",
        "catalogRevisionNumber",
        "epgExternalChannelId",
    ],
)
data class EpgChannelMatchEntity(
    val epgSourceId: String,
    val epgRevisionNumber: Long,
    val providerSourceId: String,
    val catalogRevisionNumber: Long,
    val epgExternalChannelId: String,
    @ColumnInfo(defaultValue = "0")
    val matchPolicyVersion: Int = CURRENT_EPG_MATCH_POLICY_VERSION,
    val decision: String,
    val reasonCode: String,
    val canonicalChannelId: String?,
    val candidateCount: Int,
) {
    init {
        require(epgSourceId.isNotBlank())
        require(epgRevisionNumber > 0)
        require(providerSourceId.isNotBlank())
        require(catalogRevisionNumber > 0)
        require(epgExternalChannelId.isNotBlank())
        require(matchPolicyVersion >= LEGACY_UNVERSIONED_MATCH_POLICY_VERSION)
        require(decision in VALID_DECISIONS)
        require(reasonCode.isNotBlank())
        require(candidateCount >= 0)

        when (EpgChannelMatchDecision.valueOf(decision)) {
            EpgChannelMatchDecision.MATCHED -> {
                require(!canonicalChannelId.isNullOrBlank())
                require(candidateCount == 1)
            }

            EpgChannelMatchDecision.UNRESOLVED -> {
                require(canonicalChannelId == null)
                require(candidateCount == 0)
            }

            EpgChannelMatchDecision.AMBIGUOUS -> {
                require(canonicalChannelId == null)
                require(candidateCount >= 2)
            }
        }
    }

    override fun toString(): String =
        "EpgChannelMatchEntity(matchPolicyVersion=$matchPolicyVersion, decision=$decision, " +
            "reasonCode=$reasonCode, canonicalChannelPresent=${canonicalChannelId != null}, " +
            "candidateCount=$candidateCount)"

    private companion object {
        val VALID_DECISIONS = EpgChannelMatchDecision.entries.mapTo(mutableSetOf()) { it.name }
    }
}

package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query

internal data class CatalogShadowActiveRow(
    val ordinal: Long,
    val variantId: String,
    val logicalChannelId: String,
    val payloadId: String,
    val contentHash: String,
    val canonicalChannelId: String,
    val providerKey: String,
    val rawName: String,
    val groupTitle: String?,
    val channelNumber: String?,
    val locator: String,
)

@Dao
internal interface CatalogShadowDao {
    @Query("SELECT COUNT(*) FROM catalog_payloads")
    suspend fun payloadCount(): Int

    @Query("SELECT COUNT(*) FROM catalog_search_payloads")
    suspend fun searchPayloadCount(): Int

    @Query("SELECT COUNT(*) FROM source_revision_memberships")
    suspend fun membershipCount(): Int

    @Query(
        """
        SELECT m.ordinal AS ordinal,
               m.variantId AS variantId,
               m.logicalChannelId AS logicalChannelId,
               p.payloadId AS payloadId,
               p.contentHash AS contentHash,
               p.canonicalChannelId AS canonicalChannelId,
               p.providerKey AS providerKey,
               p.rawName AS rawName,
               p.groupTitle AS groupTitle,
               p.channelNumber AS channelNumber,
               p.locator AS locator
        FROM sources AS s
        INNER JOIN source_revision_memberships AS m
            ON m.sourceId = s.id
           AND m.revisionNumber = s.activeRevision
        INNER JOIN catalog_payloads AS p
            ON p.payloadId = m.payloadId
        WHERE s.id = :sourceId
        ORDER BY m.ordinal
        """,
    )
    suspend fun activeRows(sourceId: String): List<CatalogShadowActiveRow>

    @Query(
        """
        SELECT COUNT(*)
        FROM source_revision_memberships
        WHERE sourceId = :sourceId
          AND revisionNumber = 0
        """,
    )
    suspend fun legacyRevisionZeroMembershipCount(sourceId: String): Int
}

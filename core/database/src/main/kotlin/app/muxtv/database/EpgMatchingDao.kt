package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

internal data class EpgMatchingRelationSnapshot(
    val epgSourceId: String,
    val epgRevisionNumber: Long,
    val providerSourceId: String,
    val catalogRevisionNumber: Long,
) {
    init {
        require(epgSourceId.isNotBlank())
        require(epgRevisionNumber > 0)
        require(providerSourceId.isNotBlank())
        require(catalogRevisionNumber > 0)
    }

    override fun toString(): String =
        "EpgMatchingRelationSnapshot(epgRevisionNumber=$epgRevisionNumber, " +
            "catalogRevisionNumber=$catalogRevisionNumber)"
}

internal data class EpgMatchInputChannel(
    val externalId: String,
    val primaryDisplayName: String?,
) {
    override fun toString(): String =
        "EpgMatchInputChannel(displayNamePresent=${primaryDisplayName != null})"
}

internal data class EpgMatchEvidenceRow(
    val canonicalChannelId: String,
    val providerValue: String?,
) {
    override fun toString(): String =
        "EpgMatchEvidenceRow(providerValuePresent=${providerValue != null})"
}

internal data class EpgMatchFreshnessCounts(
    val epgChannelCount: Long,
    val currentPolicyMatchCount: Long,
) {
    init {
        require(epgChannelCount >= 0)
        require(currentPolicyMatchCount >= 0)
    }
}

internal sealed interface EpgMatchPublicationResult {
    data object Applied : EpgMatchPublicationResult
    data object Superseded : EpgMatchPublicationResult
}

@Dao
internal abstract class EpgMatchingDao {
    internal data class RelationProjection(
        val epgSourceId: String,
        val epgRevisionNumber: Long,
        val providerSourceId: String?,
        val catalogRevisionNumber: Long,
    )

    @Query(
        """
        SELECT epg_sources.id AS epgSourceId,
               epg_sources.activeRevision AS epgRevisionNumber,
               epg_sources.providerSourceId AS providerSourceId,
               sources.activeRevision AS catalogRevisionNumber
        FROM epg_sources
        INNER JOIN sources ON sources.id = epg_sources.providerSourceId
        WHERE epg_sources.id = :epgSourceId
          AND epg_sources.providerSourceId IS NOT NULL
          AND epg_sources.activeRevision > 0
          AND sources.activeRevision > 0
        LIMIT 1
        """,
    )
    protected abstract suspend fun relationProjection(
        epgSourceId: String,
    ): RelationProjection?

    open suspend fun relationSnapshot(epgSourceId: String): EpgMatchingRelationSnapshot? {
        val projection = relationProjection(epgSourceId) ?: return null
        val providerSourceId = projection.providerSourceId?.takeIf(String::isNotBlank) ?: return null
        return EpgMatchingRelationSnapshot(
            epgSourceId = projection.epgSourceId,
            epgRevisionNumber = projection.epgRevisionNumber,
            providerSourceId = providerSourceId,
            catalogRevisionNumber = projection.catalogRevisionNumber,
        )
    }

    @Query(
        """
        SELECT
            (
                SELECT COUNT(*)
                FROM epg_channels
                WHERE sourceId = :epgSourceId
                  AND revisionNumber = :epgRevisionNumber
            ) AS epgChannelCount,
            (
                SELECT COUNT(*)
                FROM epg_channel_matches
                WHERE epgSourceId = :epgSourceId
                  AND epgRevisionNumber = :epgRevisionNumber
                  AND providerSourceId = :providerSourceId
                  AND catalogRevisionNumber = :catalogRevisionNumber
                  AND matchPolicyVersion = :matchPolicyVersion
            ) AS currentPolicyMatchCount
        """,
    )
    protected abstract suspend fun freshnessCounts(
        epgSourceId: String,
        epgRevisionNumber: Long,
        providerSourceId: String,
        catalogRevisionNumber: Long,
        matchPolicyVersion: Int,
    ): EpgMatchFreshnessCounts

    @Transaction
    open suspend fun isFresh(
        snapshot: EpgMatchingRelationSnapshot,
        matchPolicyVersion: Int,
    ): Boolean {
        require(matchPolicyVersion > LEGACY_UNVERSIONED_MATCH_POLICY_VERSION)
        if (relationSnapshot(snapshot.epgSourceId) != snapshot) return false
        val counts = freshnessCounts(
            epgSourceId = snapshot.epgSourceId,
            epgRevisionNumber = snapshot.epgRevisionNumber,
            providerSourceId = snapshot.providerSourceId,
            catalogRevisionNumber = snapshot.catalogRevisionNumber,
            matchPolicyVersion = matchPolicyVersion,
        )
        return counts.epgChannelCount == counts.currentPolicyMatchCount
    }

    @Query(
        """
        SELECT id
        FROM epg_sources
        WHERE providerSourceId = :providerSourceId
          AND activeRevision > 0
        ORDER BY id COLLATE BINARY ASC
        """,
    )
    abstract suspend fun linkedActiveEpgSourceIds(
        providerSourceId: String,
    ): List<String>

    @Query(
        """
        SELECT externalId, primaryDisplayName
        FROM epg_channels
        WHERE sourceId = :epgSourceId
          AND revisionNumber = :epgRevisionNumber
        ORDER BY externalId COLLATE BINARY ASC
        """,
    )
    abstract suspend fun epgChannels(
        epgSourceId: String,
        epgRevisionNumber: Long,
    ): List<EpgMatchInputChannel>

    @Query(
        """
        SELECT DISTINCT stream_variants.canonicalChannelId AS canonicalChannelId,
               provider_channels.tvgId AS providerValue
        FROM provider_channels
        INNER JOIN stream_variants
            ON stream_variants.providerChannelId = provider_channels.id
        WHERE provider_channels.sourceId = :providerSourceId
          AND provider_channels.revisionNumber = :catalogRevisionNumber
          AND provider_channels.tvgId IS NOT NULL
        ORDER BY stream_variants.canonicalChannelId COLLATE BINARY ASC,
                 provider_channels.tvgId COLLATE BINARY ASC
        """,
    )
    abstract suspend fun providerIdEvidence(
        providerSourceId: String,
        catalogRevisionNumber: Long,
    ): List<EpgMatchEvidenceRow>

    @Query(
        """
        SELECT DISTINCT stream_variants.canonicalChannelId AS canonicalChannelId,
               provider_channels.tvgName AS providerValue
        FROM provider_channels
        INNER JOIN stream_variants
            ON stream_variants.providerChannelId = provider_channels.id
        WHERE provider_channels.sourceId = :providerSourceId
          AND provider_channels.revisionNumber = :catalogRevisionNumber
          AND provider_channels.tvgName IS NOT NULL
        ORDER BY stream_variants.canonicalChannelId COLLATE BINARY ASC,
                 provider_channels.tvgName COLLATE BINARY ASC
        """,
    )
    abstract suspend fun providerTvgNameEvidence(
        providerSourceId: String,
        catalogRevisionNumber: Long,
    ): List<EpgMatchEvidenceRow>

    @Query(
        """
        SELECT DISTINCT stream_variants.canonicalChannelId AS canonicalChannelId,
               provider_channels.rawName AS providerValue
        FROM provider_channels
        INNER JOIN stream_variants
            ON stream_variants.providerChannelId = provider_channels.id
        WHERE provider_channels.sourceId = :providerSourceId
          AND provider_channels.revisionNumber = :catalogRevisionNumber
        ORDER BY stream_variants.canonicalChannelId COLLATE BINARY ASC,
                 provider_channels.rawName COLLATE BINARY ASC
        """,
    )
    abstract suspend fun providerRawNameEvidence(
        providerSourceId: String,
        catalogRevisionNumber: Long,
    ): List<EpgMatchEvidenceRow>

    @Query("DELETE FROM epg_channel_matches WHERE epgSourceId = :epgSourceId")
    protected abstract suspend fun deleteMatchesForEpgSource(epgSourceId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMatches(matches: List<EpgChannelMatchEntity>)

    @Query(
        """
        SELECT *
        FROM epg_channel_matches
        WHERE epgSourceId = :epgSourceId
        ORDER BY epgExternalChannelId COLLATE BINARY ASC
        """,
    )
    abstract suspend fun matchesForEpgSource(
        epgSourceId: String,
    ): List<EpgChannelMatchEntity>

    @Transaction
    open suspend fun replaceIfCurrent(
        snapshot: EpgMatchingRelationSnapshot,
        matches: List<EpgChannelMatchEntity>,
    ): EpgMatchPublicationResult {
        if (relationSnapshot(snapshot.epgSourceId) != snapshot) {
            return EpgMatchPublicationResult.Superseded
        }
        require(
            matches.all { match ->
                match.epgSourceId == snapshot.epgSourceId &&
                    match.epgRevisionNumber == snapshot.epgRevisionNumber &&
                    match.providerSourceId == snapshot.providerSourceId &&
                    match.catalogRevisionNumber == snapshot.catalogRevisionNumber
            },
        ) { "All EPG match rows must belong to the captured producer relation." }

        deleteMatchesForEpgSource(snapshot.epgSourceId)
        if (matches.isNotEmpty()) insertMatches(matches)
        return EpgMatchPublicationResult.Applied
    }
}

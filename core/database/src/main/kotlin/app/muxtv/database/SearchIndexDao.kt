package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query

@Dao
internal interface SearchIndexDao {
    @Query(
        """
        SELECT text
        FROM search_documents
        WHERE canonicalChannelId = :canonicalChannelId
          AND kind = :kind
        ORDER BY documentKey COLLATE BINARY
        """,
    )
    suspend fun textsForCanonicalKind(
        canonicalChannelId: String,
        kind: String,
    ): List<String>

    @Query(
        """
        SELECT text
        FROM search_documents
        WHERE kind IN (:kinds)
          AND providerChannelId IS NOT NULL
        ORDER BY documentKey COLLATE BINARY
        """,
    )
    suspend fun textsForProviderKinds(kinds: List<String>): List<String>

    @Query(
        """
        SELECT text
        FROM search_documents
        WHERE epgSourceId = :sourceId
          AND epgRevisionNumber = :revisionNumber
          AND kind = '${SearchDocumentKind.EPG_PROGRAMME_TITLE}'
        ORDER BY documentKey COLLATE BINARY
        """,
    )
    suspend fun textsForEpgRevision(
        sourceId: String,
        revisionNumber: Long,
    ): List<String>
}

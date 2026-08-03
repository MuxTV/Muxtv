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

    @Query("SELECT rowid FROM search_documents WHERE documentKey = :documentKey LIMIT 1")
    suspend fun rowIdForDocumentKey(documentKey: String): Long?

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
        WHERE profileId = :profileId
          AND canonicalChannelId = :canonicalChannelId
          AND kind IN (:kinds)
        ORDER BY documentKey COLLATE BINARY
        """,
    )
    suspend fun textsForProfileCanonicalKinds(
        profileId: String,
        canonicalChannelId: String,
        kinds: List<String>,
    ): List<String>

    @Query(
        """
        SELECT text
        FROM search_documents
        WHERE kind = '${SearchDocumentKind.EPG_PROGRAMME_TITLE}'
        ORDER BY text COLLATE BINARY
        """,
    )
    suspend fun programmeTitleTexts(): List<String>
}

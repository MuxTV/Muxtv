package app.muxtv.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "search_documents",
    indices = [
        Index(value = ["documentKey"], unique = true),
        Index(value = ["canonicalChannelId"]),
        Index(value = ["profileId", "canonicalChannelId"]),
        Index(value = ["providerChannelId"]),
        Index(value = ["kind"]),
    ],
)
data class SearchDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    val documentKey: String,
    val kind: String,
    val canonicalChannelId: String? = null,
    val profileId: String? = null,
    val providerChannelId: String? = null,
    val text: String,
) {
    init {
        require(documentKey.isNotBlank())
        require(kind in SearchDocumentKind.ALL)
        require(text.isNotBlank())
    }

    override fun toString(): String =
        "SearchDocumentEntity(kind=$kind, hasCanonical=${canonicalChannelId != null}, " +
            "hasProfile=${profileId != null}, hasProvider=${providerChannelId != null}, " +
            "text=<redacted>)"
}

internal object SearchDocumentKind {
    const val CANONICAL_NAME = "CANONICAL_NAME"
    const val PROVIDER_RAW_NAME = "PROVIDER_RAW_NAME"
    const val PROVIDER_GROUP = "PROVIDER_GROUP"
    const val PROVIDER_NUMBER = "PROVIDER_NUMBER"
    const val OVERLAY_CUSTOM_NAME = "OVERLAY_CUSTOM_NAME"
    const val OVERLAY_NUMBER = "OVERLAY_NUMBER"
    const val EPG_PROGRAMME_TITLE = "EPG_PROGRAMME_TITLE"

    val ALL = setOf(
        CANONICAL_NAME,
        PROVIDER_RAW_NAME,
        PROVIDER_GROUP,
        PROVIDER_NUMBER,
        OVERLAY_CUSTOM_NAME,
        OVERLAY_NUMBER,
        EPG_PROGRAMME_TITLE,
    )
}

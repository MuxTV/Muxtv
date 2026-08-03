package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions

@Entity(tableName = "search_documents_fts")
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    contentEntity = SearchDocumentEntity::class,
)
data class SearchDocumentFtsEntity(
    val text: String,
)

package app.muxtv.database

internal const val SEARCH_DOCUMENT_LOOKUP_BATCH_SIZE = 400

internal data class SearchDocumentWritePlan(
    val inserts: List<SearchDocumentEntity>,
    val updates: List<SearchDocumentEntity>,
)

internal fun planSearchDocumentWrites(
    desired: List<SearchDocumentEntity>,
    existing: List<SearchDocumentEntity>,
): SearchDocumentWritePlan {
    require(desired.size <= SEARCH_DOCUMENT_LOOKUP_BATCH_SIZE)
    require(desired.map(SearchDocumentEntity::documentKey).distinct().size == desired.size) {
        "Search document batch contains duplicate keys."
    }

    val existingByKey = existing.associateBy(SearchDocumentEntity::documentKey)
    val inserts = ArrayList<SearchDocumentEntity>()
    val updates = ArrayList<SearchDocumentEntity>()

    desired.forEach { document ->
        val current = existingByKey[document.documentKey]
        if (current == null) {
            inserts += document.copy(rowId = 0)
        } else {
            val update = document.copy(rowId = current.rowId)
            if (update != current) updates += update
        }
    }

    return SearchDocumentWritePlan(
        inserts = inserts,
        updates = updates,
    )
}

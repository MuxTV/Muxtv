package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.database.SourceRevisionStore

object CatalogRevisionImporterFactory {
    fun create(revisionStore: SourceRevisionStore): CatalogRevisionImporter =
        CatalogRevisionImporter(
            parser = StreamingM3uParser(),
            revisionStore = revisionStore,
        )
}

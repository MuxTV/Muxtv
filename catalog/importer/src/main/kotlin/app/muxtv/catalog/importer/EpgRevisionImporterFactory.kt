package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.StreamingXmltvParser
import app.muxtv.database.EpgRevisionStore

object EpgRevisionImporterFactory {
    fun create(revisionStore: EpgRevisionStore): EpgRevisionImporter =
        EpgRevisionImporter(
            parser = StreamingXmltvParser(),
            revisionStore = revisionStore,
        )
}
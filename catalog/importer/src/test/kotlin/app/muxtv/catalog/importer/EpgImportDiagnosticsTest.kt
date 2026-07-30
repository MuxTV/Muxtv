package app.muxtv.catalog.importer

import app.muxtv.database.EpgSourceDefinition
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgImportDiagnosticsTest {
    @Test
    fun requestAndSourceDefinitionDoNotExposeCallerValues() {
        val request = EpgImportRequest(
            sourceId = "private-source-id",
            sourceName = "Private source name",
            providerSourceId = "private-provider-id",
            accessRef = "private-access-ref",
            defaultZoneId = "Private/Zone",
        )
        val definition = EpgSourceDefinition(
            id = request.sourceId,
            name = request.sourceName,
            providerSourceId = request.providerSourceId,
            accessRef = request.accessRef,
            defaultZoneId = request.defaultZoneId,
        )

        val diagnostics = "$request | $definition"
        assertThat(diagnostics).doesNotContain("private-source-id")
        assertThat(diagnostics).doesNotContain("Private source name")
        assertThat(diagnostics).doesNotContain("private-provider-id")
        assertThat(diagnostics).doesNotContain("private-access-ref")
        assertThat(diagnostics).doesNotContain("Private/Zone")
    }
}

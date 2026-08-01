package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceRefreshEntityRedactionTest {
    @Test
    fun `source and refresh models do not expose credential or run token values`() {
        val credential = "sensitive-credential-ref"
        val runToken = "sensitive-source-run-token"
        val models = listOf(
            SourceDefinition(
                id = "source-1",
                name = "Source",
                credentialRef = credential,
            ),
            SourceRefreshTarget(
                sourceId = "source-1",
                sourceName = "Source",
                credentialRef = credential,
            ),
            SourceRefreshTargetRow(
                sourceId = "source-1",
                sourceName = "Source",
                credentialRef = credential,
            ),
            SourceRemovalSnapshot(
                activeRevision = 1,
                credentialRef = credential,
            ),
            SourceEntity(
                id = "source-1",
                name = "Source",
                credentialRef = credential,
            ),
            SourceRefreshStateEntity(
                sourceId = "source-1",
                state = SourceRefreshRunState.RUNNING.name,
                runToken = runToken,
                startedAtEpochMillis = 100,
            ),
            SourceRefreshAttemptEntity(
                sourceId = "source-1",
                runToken = runToken,
                trigger = SourceRefreshTrigger.MANUAL.name,
                startedAtEpochMillis = 100,
                completedAtEpochMillis = 120,
                resultState = SourceRefreshRunState.CANCELLED.name,
                resultFamily = "SOURCE_REFRESH",
                resultCode = "CANCELLED",
            ),
        )

        models.forEach { model ->
            val text = model.toString()
            assertThat(text).doesNotContain(credential)
            assertThat(text).doesNotContain(runToken)
        }
    }
}

package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgRefreshEntityRedactionTest {
    @Test
    fun `refresh state and attempt do not expose run token values`() {
        val runToken = "sensitive-run-token-value"
        val state = EpgRefreshStateEntity(
            sourceId = "epg-source-1",
            state = EpgRefreshRunState.RUNNING.name,
            runToken = runToken,
            startedAtEpochMillis = 100,
        )
        val attempt = EpgRefreshAttemptEntity(
            sourceId = "epg-source-1",
            runToken = runToken,
            trigger = EpgRefreshTrigger.MANUAL.name,
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 120,
            resultState = EpgRefreshRunState.CANCELLED.name,
            resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
            resultCode = "CANCELLED",
        )

        assertThat(state.toString()).doesNotContain(runToken)
        assertThat(attempt.toString()).doesNotContain(runToken)
    }

    @Test
    fun `validator entity does not expose access binding or validator values`() {
        val entity = EpgRefreshHttpValidatorEntity(
            sourceId = "epg-source-1",
            accessRefBinding = "sensitive-access-binding",
            etag = "sensitive-etag",
            lastModified = "sensitive-last-modified",
            updatedAtEpochMillis = 100,
        )

        val text = entity.toString()
        assertThat(text).doesNotContain("sensitive-access-binding")
        assertThat(text).doesNotContain("sensitive-etag")
        assertThat(text).doesNotContain("sensitive-last-modified")
    }
}

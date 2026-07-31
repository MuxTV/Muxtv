package app.muxtv.database

import org.junit.Assert.assertThrows
import org.junit.Test

class EpgStageBatchValidationTest {
    @Test
    fun `accepts one bounded revision batch`() {
        validateEpgStageBatch(
            channels = listOf(channel("source-1", 1)),
            programmes = listOf(programme("source-1", 1)),
        )
    }

    @Test
    fun `rejects rows from different sources or revisions`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEpgStageBatch(
                channels = listOf(channel("source-1", 1)),
                programmes = listOf(programme("source-2", 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateEpgStageBatch(
                channels = listOf(channel("source-1", 1)),
                programmes = listOf(programme("source-1", 2)),
            )
        }
    }

    @Test
    fun `rejects batches larger than production maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateEpgStageBatch(
                channels = List(1_001) { index -> channel("source-1", 1, "channel-$index") },
                programmes = emptyList(),
            )
        }
    }

    private fun channel(
        sourceId: String,
        revisionNumber: Long,
        externalId: String = "channel",
    ): EpgChannelEntity = EpgChannelEntity(
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        externalId = externalId,
        primaryDisplayName = null,
        primaryLanguage = null,
        iconRef = null,
    )

    private fun programme(sourceId: String, revisionNumber: Long): EpgProgrammeEntity =
        EpgProgrammeEntity(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            sequenceNumber = 1,
            externalChannelId = "channel",
            startEpochMillis = 1_000,
            stopEpochMillis = 2_000,
            primaryTitle = null,
            primaryLanguage = null,
            subtitle = null,
            description = null,
            category = null,
            iconRef = null,
            episodeNumber = null,
            isNew = false,
        )
}

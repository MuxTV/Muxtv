package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.StreamingM3uParser
import app.muxtv.database.InactiveSourceRemovalResult
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.SourceRevisionStore
import app.muxtv.database.StagedCatalogEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogNeutralImportContractTest {
    @Test
    fun `neutral feed stages an opaque second-provider playback reference without m3u models`() = runTest {
        val store = RecordingStore()
        val importer = importer(store)
        val entry = xtreamEntry(
            providerStableId = "707",
            displayName = "Synthetic Live",
            playbackReference = "muxtv-provider://xtream/live/707",
        )

        val result = importer.importEntries(
            request = remoteRequest(),
            feed = singleEntryFeed(
                entry = entry,
                report = CatalogImportFeedReport(
                    parsedEntries = 1,
                    skippedEntries = 2,
                    warningCount = 3,
                ),
            ),
        )

        assertThat(result).isInstanceOf(CatalogImportResult.Imported::class.java)
        val imported = result as CatalogImportResult.Imported
        assertThat(imported.skippedEntries).isEqualTo(2)
        assertThat(imported.warningCount).isEqualTo(3)
        assertThat(store.activationStatistics.single()).isEqualTo(
            SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 2,
                warningCount = 3,
            ),
        )

        val staged = store.batches.single().single()
        assertThat(staged.providerKey).isEqualTo("provider:707")
        assertThat(staged.rawName).isEqualTo("Synthetic Live")
        assertThat(staged.locator).isEqualTo("muxtv-provider://xtream/live/707")
        assertThat(staged.locator).doesNotContain("TEST_USER")
        assertThat(staged.locator).doesNotContain("TEST_PASS")
    }

    @Test
    fun `provider stable identity is source scoped and survives display rename`() = runTest {
        val store = RecordingStore()
        val importer = importer(store)

        importer.importEntries(
            request = remoteRequest(sourceId = "source-a"),
            feed = singleEntryFeed(
                xtreamEntry(
                    providerStableId = "707",
                    displayName = "Old Name",
                    playbackReference = "muxtv-provider://xtream/live/707",
                ),
            ),
        )
        val first = store.batches.last().single()

        importer.importEntries(
            request = remoteRequest(sourceId = "source-a"),
            feed = singleEntryFeed(
                xtreamEntry(
                    providerStableId = "707",
                    displayName = "Renamed Channel",
                    playbackReference = "muxtv-provider://xtream/live/707",
                ),
            ),
        )
        val renamed = store.batches.last().single()

        val otherStore = RecordingStore()
        val otherImporter = importer(otherStore)
        otherImporter.importEntries(
            request = remoteRequest(sourceId = "source-b"),
            feed = singleEntryFeed(
                xtreamEntry(
                    providerStableId = "707",
                    displayName = "Renamed Channel",
                    playbackReference = "muxtv-provider://xtream/live/707",
                ),
            ),
        )
        val otherSource = otherStore.batches.single().single()

        assertThat(first.providerKey).isEqualTo("provider:707")
        assertThat(renamed.providerKey).isEqualTo("provider:707")
        assertThat(first.canonicalChannelId).isEqualTo(renamed.canonicalChannelId)
        assertThat(first.canonicalChannelId).isNotEqualTo(otherSource.canonicalChannelId)
        assertThat(first.providerChannelId).isNotEqualTo(renamed.providerChannelId)
        assertThat(first.streamVariantId).isNotEqualTo(renamed.streamVariantId)
    }

    @Test
    fun `neutral feed uses the existing bounded staging batches`() = runTest {
        val store = RecordingStore()
        val importer = importer(store)
        val entries = (1..251).map { index ->
            xtreamEntry(
                providerStableId = index.toString(),
                displayName = "Synthetic $index",
                playbackReference = "muxtv-provider://xtream/live/$index",
            )
        }

        val result = importer.importEntries(
            request = remoteRequest(),
            feed = object : CatalogImportFeed {
                override suspend fun streamTo(sink: CatalogImportEntrySink): CatalogImportFeedReport {
                    entries.forEach { sink.onEntry(it) }
                    return CatalogImportFeedReport(
                        parsedEntries = entries.size,
                        skippedEntries = 0,
                        warningCount = 0,
                    )
                }
            },
        )

        assertThat(result).isInstanceOf(CatalogImportResult.Imported::class.java)
        assertThat(store.batches.map { it.size }).containsExactly(250, 1).inOrder()
    }

    @Test
    fun `neutral feed cancellation discards staging and remains terminal`() = runTest {
        val store = RecordingStore()
        val importer = importer(store)
        val cancellation = CancellationException("contract cancellation")

        val failure = runCatching {
            importer.importEntries(
                request = remoteRequest(),
                feed = object : CatalogImportFeed {
                    override suspend fun streamTo(sink: CatalogImportEntrySink): CatalogImportFeedReport {
                        sink.onEntry(
                            xtreamEntry(
                                providerStableId = "707",
                                displayName = "Synthetic Live",
                                playbackReference = "muxtv-provider://xtream/live/707",
                            ),
                        )
                        throw cancellation
                    }
                },
            )
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(store.discardedRevisions).containsExactly(1L)
        assertThat(store.activationStatistics).isEmpty()
    }

    private fun importer(store: RecordingStore): CatalogRevisionImporter = CatalogRevisionImporter(
        parser = StreamingM3uParser(),
        revisionStore = store,
        nowEpochMillis = { 1_000L },
    )

    private fun remoteRequest(sourceId: String = "source-xtream") = CatalogImportRequest(
        sourceId = sourceId,
        sourceName = "Synthetic Xtream",
        credentialRef = "credential-ref",
        refreshRunToken = "run-token",
        sourceOwnership = CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING,
    )

    private fun xtreamEntry(
        providerStableId: String,
        displayName: String,
        playbackReference: String,
    ) = CatalogImportEntry(
        providerStableId = providerStableId,
        displayName = displayName,
        playbackReference = playbackReference,
        tvgId = null,
        tvgName = null,
        logoUrl = null,
        groupTitle = "Synthetic",
        channelNumber = null,
        catchupMode = null,
        catchupSource = null,
        catchupDays = null,
        catchupCorrection = null,
        userAgent = null,
        referrer = null,
    )

    private fun singleEntryFeed(
        entry: CatalogImportEntry,
        report: CatalogImportFeedReport = CatalogImportFeedReport(
            parsedEntries = 1,
            skippedEntries = 0,
            warningCount = 0,
        ),
    ): CatalogImportFeed = object : CatalogImportFeed {
        override suspend fun streamTo(sink: CatalogImportEntrySink): CatalogImportFeedReport {
            sink.onEntry(entry)
            return report
        }
    }

    private class RecordingStore : SourceRevisionStore {
        val batches = mutableListOf<List<StagedCatalogEntry>>()
        val activationStatistics = mutableListOf<SourceRevisionStatistics>()
        val discardedRevisions = mutableListOf<Long>()
        private var nextRevision = 1L

        override suspend fun upsertSource(source: SourceDefinition) = Unit

        override suspend fun nextRevisionNumber(sourceId: String): Long = nextRevision++

        override suspend fun beginRevision(
            sourceId: String,
            revisionNumber: Long,
            startedAtEpochMillis: Long,
        ) = Unit

        override suspend fun stageBatch(
            sourceId: String,
            revisionNumber: Long,
            entries: List<StagedCatalogEntry>,
        ) {
            batches += entries
        }

        override suspend fun activate(
            sourceId: String,
            revisionNumber: Long,
            activatedAtEpochMillis: Long,
            statistics: SourceRevisionStatistics,
        ): SourceRevisionActivationResult = activated(revisionNumber, statistics)

        override suspend fun activateIfCredentialMatches(
            sourceId: String,
            revisionNumber: Long,
            expectedCredentialRef: String,
            activatedAtEpochMillis: Long,
            statistics: SourceRevisionStatistics,
        ): SourceRevisionActivationResult = activated(revisionNumber, statistics)

        override suspend fun activateIfRefreshOwnerMatches(
            sourceId: String,
            revisionNumber: Long,
            expectedCredentialRef: String,
            expectedRunToken: String,
            activatedAtEpochMillis: Long,
            statistics: SourceRevisionStatistics,
        ): SourceRevisionActivationResult = activated(revisionNumber, statistics)

        private fun activated(
            revisionNumber: Long,
            statistics: SourceRevisionStatistics,
        ): SourceRevisionActivationResult {
            activationStatistics += statistics
            return SourceRevisionActivationResult.Activated(
                revisionNumber = revisionNumber,
                previousRevisionNumber = revisionNumber - 1,
                entryCount = batches.takeLastWhile { it.isNotEmpty() }.sumOf { it.size },
            )
        }

        override suspend fun discard(sourceId: String, revisionNumber: Long) {
            discardedRevisions += revisionNumber
        }

        override suspend fun removeInactiveSource(
            sourceId: String,
            expectedCredentialRef: String,
        ): InactiveSourceRemovalResult = InactiveSourceRemovalResult.NotFound
    }
}

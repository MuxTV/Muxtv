package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.GuideChannelWindowQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuideChannelWindowRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore
    private lateinit var repository: RoomGuideWindowRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        val epgGuideRepository = RoomEpgGuideRepository(database.epgGuideDao())
        repository = RoomGuideWindowRepository(
            dao = database.guideWindowDao(),
            invalidationSource = epgGuideRepository,
        )

        sourceStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Guide source",
            ),
        )
        stageRevision(
            revisionNumber = 1,
            entries = listOf(
                entry(1, "provider-a-1", CHANNEL_A, "Zulu", "variant-a-1"),
                entry(1, "provider-a-2", CHANNEL_A, "Zulu mirror", "variant-a-2"),
                entry(1, "provider-b", CHANNEL_B, "Hidden", "variant-b"),
                entry(1, "provider-c", CHANNEL_C, "Alpha", "variant-c"),
                entry(1, "provider-d", CHANNEL_D, "Beta", "variant-d"),
                entry(1, "provider-e", CHANNEL_E, "Omega", "variant-e"),
            ),
        )
        activateRevision(revisionNumber = 1, parsedEntries = 6)
        stageRevision(
            revisionNumber = 2,
            entries = listOf(
                entry(2, "provider-d-r2", CHANNEL_D, "Beta current", "variant-d-r2"),
                entry(2, "provider-f-r2", CHANNEL_F, "Gamma", "variant-f-r2"),
            ),
        )

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_A,
                customName = "Number Two",
                channelNumber = 2,
            ),
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_E,
                customName = "Number Seven",
                channelNumber = 7,
            ),
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_B,
                isHidden = true,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keysetPagesAreDeterministicAndRevisionSafe() = runTest {
        val first = repository.getChannelWindow(
            GuideChannelWindowQuery(
                profileId = PROFILE_ID,
                limit = 2,
            ),
        )

        assertThat(first.channels.map { channel -> channel.channelId })
            .containsExactly(CHANNEL_A, CHANNEL_E)
            .inOrder()
        assertThat(first.channels.first().variantCount).isEqualTo(2)
        assertThat(first.channels.first().displayName).isEqualTo("Number Two")
        assertThat(first.isTruncated).isTrue()
        assertThat(first.nextCursor).isNotNull()
        assertThat(first.nextCursor!!.channelNumber).isEqualTo(7)
        assertThat(first.nextCursor!!.displayName).isEqualTo("Number Seven")
        assertThat(first.nextCursor!!.canonicalChannelId).isEqualTo(CHANNEL_E)

        val second = repository.getChannelWindow(
            GuideChannelWindowQuery(
                profileId = PROFILE_ID,
                after = first.nextCursor,
                limit = 2,
            ),
        )

        assertThat(second.channels.map { channel -> channel.channelId })
            .containsExactly(CHANNEL_C, CHANNEL_D)
            .inOrder()
        assertThat(second.isTruncated).isFalse()
        assertThat(second.nextCursor).isNull()
        assertThat(
            (first.channels + second.channels).map { channel -> channel.channelId },
        ).doesNotContain(CHANNEL_B)
        assertThat(
            (first.channels + second.channels).map { channel -> channel.channelId },
        ).doesNotContain(CHANNEL_F)

        activateRevision(revisionNumber = 2, parsedEntries = 2)

        val afterSwap = repository.getChannelWindow(
            GuideChannelWindowQuery(
                profileId = PROFILE_ID,
                limit = 10,
            ),
        )
        assertThat(afterSwap.channels.map { channel -> channel.channelId })
            .containsExactly(CHANNEL_D, CHANNEL_F)
            .inOrder()
        assertThat(afterSwap.channels.map { channel -> channel.channelId })
            .containsNoneOf(CHANNEL_A, CHANNEL_B, CHANNEL_C, CHANNEL_E)

        val staleCursorContinuation = repository.getChannelWindow(
            GuideChannelWindowQuery(
                profileId = PROFILE_ID,
                after = first.nextCursor,
                limit = 10,
            ),
        )
        assertThat(staleCursorContinuation.channels.map { channel -> channel.channelId })
            .containsExactly(CHANNEL_D, CHANNEL_F)
            .inOrder()
    }

    private suspend fun stageRevision(
        revisionNumber: Long,
        entries: List<StagedCatalogEntry>,
    ) {
        sourceStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = revisionNumber * 1_000L,
        )
        sourceStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            entries = entries,
        )
    }

    private suspend fun activateRevision(
        revisionNumber: Long,
        parsedEntries: Int,
    ) {
        assertThat(
            sourceStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = revisionNumber,
                activatedAtEpochMillis = revisionNumber * 1_000L + 500L,
                statistics = SourceRevisionStatistics(
                    parsedEntries = parsedEntries,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun entry(
        revisionNumber: Long,
        providerChannelId: String,
        canonicalChannelId: String,
        displayName: String,
        variantId: String,
    ): StagedCatalogEntry = StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = "provider:$providerChannelId",
        rawName = displayName,
        canonicalChannelId = canonicalChannelId,
        canonicalDisplayName = displayName,
        streamVariantId = variantId,
        locator = "https://example.invalid/$revisionNumber/$variantId.m3u8",
        groupTitle = "Guide group",
    )

    private companion object {
        const val PROFILE_ID = DatabaseDefaults.PRIMARY_PROFILE_ID
        const val SOURCE_ID = "guide-window-source"
        const val CHANNEL_A = "channel-a"
        const val CHANNEL_B = "channel-b"
        const val CHANNEL_C = "channel-c"
        const val CHANNEL_D = "channel-d"
        const val CHANNEL_E = "channel-e"
        const val CHANNEL_F = "channel-f"
    }
}

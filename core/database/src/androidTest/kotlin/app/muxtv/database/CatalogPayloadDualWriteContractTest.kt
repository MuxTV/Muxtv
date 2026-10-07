package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogPayloadDualWriteContractTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun stagingReusesPayloadButPreservesMembershipMultiplicityAndVariantIdentity() = runTest {
        store.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
        store.beginRevision(SOURCE_ID, 1, 1_000)

        store.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(
                entry(
                    providerChannelId = "provider-1-a",
                    streamVariantId = "variant-1-a",
                ),
                entry(
                    providerChannelId = "provider-1-b",
                    streamVariantId = "variant-1-b",
                ),
            ),
        )

        assertThat(database.catalogShadowDao().payloadCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().searchPayloadCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(2)

        val activation = store.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 2_000,
            statistics = SourceRevisionStatistics(2, 0, 0),
        )
        assertThat(activation).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)

        val active = database.catalogShadowDao().activeRows(SOURCE_ID)
        assertThat(active.map { it.variantId })
            .containsExactly("variant-1-a", "variant-1-b")
            .inOrder()
        assertThat(active.map { it.payloadId }.distinct()).hasSize(1)
    }

    @Test
    fun refreshKeepsPreviousGoodMembershipAndTokenChurnCreatesNewPayloadOnly() = runTest {
        store.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))

        activate(
            revision = 1,
            variantId = "variant-r1",
            locator = "https://stream.invalid/live?token=one",
        )
        activate(
            revision = 2,
            variantId = "variant-r2",
            locator = "https://stream.invalid/live?token=one",
        )

        assertThat(database.catalogShadowDao().payloadCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().searchPayloadCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(2)

        activate(
            revision = 3,
            variantId = "variant-r3",
            locator = "https://stream.invalid/live?token=two",
        )

        // Active r3 + previous-good r2 remain. r1 membership is removed.
        assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(2)
        assertThat(database.catalogShadowDao().payloadCount()).isEqualTo(2)
        // Locator/token churn does not duplicate provider-search vocabulary.
        assertThat(database.catalogShadowDao().searchPayloadCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().activeRows(SOURCE_ID).single().variantId)
            .isEqualTo("variant-r3")
    }

    @Test
    fun activationDrainsMoreThanOneOrphanBatchWithoutGrowingStorageByRefreshCount() = runTest {
        store.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))

        activateMany(revision = 1, token = "one")
        activateMany(revision = 2, token = "one")
        activateMany(revision = 3, token = "two")
        activateMany(revision = 4, token = "three")

        // Active r4 + previous-good r3 remain. The r1/r2 "one" generation is unreachable and
        // contains 300 payloads, deliberately larger than the 250-row compaction batch.
        assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(ENTRY_COUNT * 2)
        assertThat(database.catalogShadowDao().payloadCount()).isEqualTo(ENTRY_COUNT * 2)
        assertThat(database.catalogShadowDao().searchPayloadCount()).isEqualTo(ENTRY_COUNT)
    }

    private suspend fun activateMany(
        revision: Long,
        token: String,
    ) {
        store.beginRevision(SOURCE_ID, revision, revision * 10_000)
        val entries = (0 until ENTRY_COUNT).map { index ->
            StagedCatalogEntry(
                providerChannelId = "provider-r$revision-$index",
                providerKey = "tvg:channel-$index",
                rawName = "Channel $index",
                canonicalChannelId = "canonical-$index",
                canonicalDisplayName = "Channel $index",
                streamVariantId = "variant-r$revision-$index",
                locator = "https://stream.invalid/$index?token=$token",
                tvgId = "channel-$index",
                tvgName = "Channel $index",
                groupTitle = "General",
                channelNumber = (index + 1).toString(),
            )
        }
        entries.chunked(250).forEach { batch ->
            store.stageBatch(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                entries = batch,
            )
        }
        assertThat(
            store.activate(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                activatedAtEpochMillis = revision * 10_000 + 100,
                statistics = SourceRevisionStatistics(ENTRY_COUNT, 0, 0),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private suspend fun activate(
        revision: Long,
        variantId: String,
        locator: String,
    ) {
        store.beginRevision(SOURCE_ID, revision, revision * 1_000)
        store.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revision,
            entries = listOf(
                entry(
                    providerChannelId = "provider-r$revision",
                    streamVariantId = variantId,
                    locator = locator,
                ),
            ),
        )
        val result = store.activate(
            sourceId = SOURCE_ID,
            revisionNumber = revision,
            activatedAtEpochMillis = revision * 1_000 + 100,
            statistics = SourceRevisionStatistics(1, 0, 0),
        )
        assertThat(result).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun entry(
        providerChannelId: String,
        streamVariantId: String,
        locator: String = "https://stream.invalid/live",
    ) = StagedCatalogEntry(
        providerChannelId = providerChannelId,
        providerKey = "tvg:channel-one",
        rawName = "Channel One",
        canonicalChannelId = CHANNEL_ID,
        canonicalDisplayName = "Channel One",
        streamVariantId = streamVariantId,
        locator = locator,
        tvgId = "channel-one",
        tvgName = "Channel One",
        groupTitle = "General",
        channelNumber = "1",
    )

    private companion object {
        const val SOURCE_ID = "source-c03-prod-a"
        const val CHANNEL_ID = "channel-one"
        const val ENTRY_COUNT = 300
    }
}

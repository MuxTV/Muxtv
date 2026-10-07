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
    }
}

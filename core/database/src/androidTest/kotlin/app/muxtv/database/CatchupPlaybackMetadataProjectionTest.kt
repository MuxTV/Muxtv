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
class CatchupPlaybackMetadataProjectionTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activeVariantAccessCarriesPersistedCatchupMetadataFromOwningProviderChannel() = runTest {
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "M3U source",
                credentialRef = CREDENTIAL_REF,
            ),
        )
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            startedAtEpochMillis = 1_000L,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1L,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = PROVIDER_CHANNEL_ID,
                    providerKey = "m3u:catchup-news",
                    rawName = "Catch-up News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "Catch-up News",
                    streamVariantId = VARIANT_ID,
                    locator = LIVE_LOCATOR,
                    catchupMode = "append",
                    catchupSource = CATCHUP_SOURCE,
                    catchupDays = 7,
                    catchupCorrection = "+2.0",
                ),
            ),
        )
        assertThat(
            revisionStore.activate(
                sourceId = SOURCE_ID,
                revisionNumber = 1L,
                activatedAtEpochMillis = 2_000L,
                statistics = SourceRevisionStatistics(
                    parsedEntries = 1,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)

        val row = database.playbackCatalogDao().findActiveVariantAccess(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            variantId = VARIANT_ID,
        )

        requireNotNull(row)
        assertThat(row.catchupMode).isEqualTo("append")
        assertThat(row.catchupSource).isEqualTo(CATCHUP_SOURCE)
        assertThat(row.catchupDays).isEqualTo(7)
        assertThat(row.catchupCorrection).isEqualTo("+2.0")
        assertThat(row.toString()).doesNotContain("TEST_CATCHUP_PROJECTION_SECRET")
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-m3u-catchup"
        const val CREDENTIAL_REF = "00000000-0000-0000-0000-0000000000c1"
        const val PROVIDER_CHANNEL_ID = "provider-catchup-news"
        const val CHANNEL_ID = "canonical-catchup-news"
        const val VARIANT_ID = "variant-catchup-news"
        const val LIVE_LOCATOR = "https://streams.invalid/live/news.m3u8"
        const val CATCHUP_SOURCE = "?utc={utc}&token=TEST_CATCHUP_PROJECTION_SECRET"
    }
}

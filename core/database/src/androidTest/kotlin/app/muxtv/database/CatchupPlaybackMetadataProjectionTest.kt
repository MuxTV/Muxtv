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
    fun activeVariantAccessCarriesOnlyActivePersistedCatchupMetadataAndRedactsSecrets() = runTest {
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
        stageRevision(
            revisionNumber = 1L,
            providerChannelId = ACTIVE_PROVIDER_CHANNEL_ID,
            variantId = ACTIVE_VARIANT_ID,
            locator = ACTIVE_LIVE_LOCATOR,
            catchupSource = ACTIVE_CATCHUP_SOURCE,
            catchupDays = 7,
            catchupCorrection = "+2.0",
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

        stageRevision(
            revisionNumber = 2L,
            providerChannelId = STAGED_PROVIDER_CHANNEL_ID,
            variantId = STAGED_VARIANT_ID,
            locator = STAGED_LIVE_LOCATOR,
            catchupSource = STAGED_CATCHUP_SOURCE,
            catchupDays = 3,
            catchupCorrection = "-1.0",
        )

        val row = database.playbackCatalogDao().findActiveVariantAccess(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            variantId = ACTIVE_VARIANT_ID,
        )

        requireNotNull(row)
        assertThat(row.catchupMode).isEqualTo("append")
        assertThat(row.catchupSource).isEqualTo(ACTIVE_CATCHUP_SOURCE)
        assertThat(row.catchupDays).isEqualTo(7)
        assertThat(row.catchupCorrection).isEqualTo("+2.0")
        assertThat(
            database.playbackCatalogDao().findActiveVariantAccess(
                profileId = PROFILE_ID,
                channelId = CHANNEL_ID,
                variantId = STAGED_VARIANT_ID,
            ),
        ).isNull()

        val diagnostic = row.toString()
        assertThat(diagnostic).doesNotContain(ACTIVE_CATCHUP_SECRET)
        assertThat(diagnostic).doesNotContain(STAGED_CATCHUP_SECRET)
        assertThat(diagnostic).doesNotContain(LIVE_SECRET)
        assertThat(diagnostic).doesNotContain(CREDENTIAL_REF)
    }

    private suspend fun stageRevision(
        revisionNumber: Long,
        providerChannelId: String,
        variantId: String,
        locator: String,
        catchupSource: String,
        catchupDays: Int,
        catchupCorrection: String,
    ) {
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = 1_000L + revisionNumber,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = providerChannelId,
                    providerKey = "m3u:catchup-news",
                    rawName = "Catch-up News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "Catch-up News",
                    streamVariantId = variantId,
                    locator = locator,
                    catchupMode = "append",
                    catchupSource = catchupSource,
                    catchupDays = catchupDays,
                    catchupCorrection = catchupCorrection,
                ),
            ),
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-m3u-catchup"
        const val CREDENTIAL_REF = "00000000-0000-0000-0000-0000000000c1"
        const val CHANNEL_ID = "canonical-catchup-news"
        const val ACTIVE_PROVIDER_CHANNEL_ID = "provider-catchup-news-active"
        const val STAGED_PROVIDER_CHANNEL_ID = "provider-catchup-news-staged"
        const val ACTIVE_VARIANT_ID = "variant-catchup-news-active"
        const val STAGED_VARIANT_ID = "variant-catchup-news-staged"
        const val LIVE_SECRET = "TEST_LIVE_PROJECTION_SECRET"
        const val ACTIVE_CATCHUP_SECRET = "TEST_CATCHUP_PROJECTION_SECRET"
        const val STAGED_CATCHUP_SECRET = "TEST_STAGED_CATCHUP_SECRET"
        const val ACTIVE_LIVE_LOCATOR = "https://streams.invalid/live/news.m3u8?token=$LIVE_SECRET"
        const val STAGED_LIVE_LOCATOR = "https://streams.invalid/live/news-v2.m3u8?token=$LIVE_SECRET"
        const val ACTIVE_CATCHUP_SOURCE = "?utc={utc}&token=$ACTIVE_CATCHUP_SECRET"
        const val STAGED_CATCHUP_SOURCE = "?utc={utc}&token=$STAGED_CATCHUP_SECRET"
    }
}

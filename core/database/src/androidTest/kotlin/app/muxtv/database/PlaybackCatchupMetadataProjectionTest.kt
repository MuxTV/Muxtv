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
class PlaybackCatchupMetadataProjectionTest {
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
    fun activeVariantAccessProjectsPersistedCatchupMetadataWithoutDiagnosticSecretLeak() = runTest {
        insertProfile()
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "M3U Provider",
                credentialRef = "credential-source",
            ),
        )
        stageRevision(
            revisionNumber = 1,
            catchupSource = "?utc={utc}&token=$ACTIVE_SECRET",
            catchupDays = 7,
            catchupCorrection = "+2.0",
        )
        activateRevision(revisionNumber = 1)

        // A newer staged revision must not leak into the active playback projection.
        stageRevision(
            revisionNumber = 2,
            catchupSource = "?utc={utc}&token=$STAGED_SECRET",
            catchupDays = 2,
            catchupCorrection = "-1.0",
        )

        val row = database.playbackCatalogDao().findActiveVariantAccess(
            profileId = PROFILE_ID,
            channelId = CHANNEL_ID,
            variantId = VARIANT_ID,
        )

        assertThat(row).isNotNull()
        checkNotNull(row)
        assertThat(row.catchupMode).isEqualTo("append")
        assertThat(row.catchupSource).isEqualTo("?utc={utc}&token=$ACTIVE_SECRET")
        assertThat(row.catchupDays).isEqualTo(7)
        assertThat(row.catchupCorrection).isEqualTo("+2.0")
        assertThat(row.toString()).doesNotContain(ACTIVE_SECRET)
        assertThat(row.toString()).doesNotContain(STAGED_SECRET)
        assertThat(row.toString()).doesNotContain("credential-source")
        assertThat(row.toString()).doesNotContain("live-token")
    }

    private suspend fun insertProfile() {
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
    }

    private suspend fun stageRevision(
        revisionNumber: Long,
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
                    providerChannelId = "provider-$revisionNumber",
                    providerKey = "tvg:news",
                    rawName = "News",
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = "News",
                    streamVariantId = VARIANT_ID,
                    locator = "https://streams.invalid/live.m3u8?token=live-token",
                    catchupMode = "append",
                    catchupSource = catchupSource,
                    catchupDays = catchupDays,
                    catchupCorrection = catchupCorrection,
                ),
            ),
        )
    }

    private suspend fun activateRevision(revisionNumber: Long) {
        val result = revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = 2_000L + revisionNumber,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        assertThat(result).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private companion object {
        const val PROFILE_ID = "profile-primary"
        const val SOURCE_ID = "source-m3u"
        const val CHANNEL_ID = "channel-news"
        const val VARIANT_ID = "variant-news"
        const val ACTIVE_SECRET = "TEST_CATCHUP_ACTIVE_SECRET"
        const val STAGED_SECRET = "TEST_CATCHUP_STAGED_SECRET"
    }
}

package app.muxtv.database

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RejectAllPlaybackAccessPolicyResolver
import app.muxtv.catalog.UnhandledPlaybackReferenceResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class X05RoomV11OverlaySurvivalTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore
    private lateinit var recent: RoomRecentChannelsRepository
    private lateinit var playback: RoomPlaybackCatalog

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
        recent = RoomRecentChannelsRepository(database.recentChannelsDao())
        playback = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = RejectAllPlaybackAccessPolicyResolver,
            playbackReferenceResolver = UnhandledPlaybackReferenceResolver,
        )
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
        store.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
        activate(
            revision = 1,
            variantId = "variant-r1",
            locator = "https://stream.invalid/live?token=one",
        )
        assertThat(recent.recordSuccessfulPlayback(PROFILE_ID, CHANNEL_ID, RECENT_AT))
            .isEqualTo(RecentChannelWriteResult.Applied)
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                isFavorite = true,
                customName = CUSTOM_NAME,
                channelNumber = CUSTOM_NUMBER,
                isHidden = true,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successfulRefreshPreservesOverlayRecentAndCanonicalIdentityInV11Shadow() = runTest {
        val before = snapshot()

        activate(
            revision = 2,
            variantId = "variant-r2",
            locator = "https://stream.invalid/live?token=two",
        )

        assertThat(snapshot()).isEqualTo(before)
        assertThat(playback.getChannel(PROFILE_ID, CHANNEL_ID)).isNull()

        val shadow = database.catalogShadowDao().activeRows(SOURCE_ID).single()
        assertThat(shadow.canonicalChannelId).isEqualTo(CHANNEL_ID)
        assertThat(shadow.variantId).isEqualTo("variant-r2")
        assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(2)
        assertThat(database.catalogShadowDao().payloadCount()).isEqualTo(2)
        assertThat(database.catalogShadowDao().searchPayloadCount()).isEqualTo(1)
    }

    @Test
    fun discardedStagingRefreshCannotChangeOverlayRecentOrActiveShadowTruth() = runTest {
        val before = snapshot()

        store.beginRevision(SOURCE_ID, 2, 3_000)
        store.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 2,
            entries = listOf(
                entry(
                    revision = 2,
                    variantId = "variant-staged",
                    locator = "https://stream.invalid/live?token=staged",
                ),
            ),
        )
        store.discard(SOURCE_ID, 2)

        assertThat(snapshot()).isEqualTo(before)
        val shadow = database.catalogShadowDao().activeRows(SOURCE_ID).single()
        assertThat(shadow.variantId).isEqualTo("variant-r1")
        assertThat(database.catalogShadowDao().membershipCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().payloadCount()).isEqualTo(1)
        assertThat(database.catalogShadowDao().searchPayloadCount()).isEqualTo(1)
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
            entries = listOf(entry(revision, variantId, locator)),
        )
        assertThat(
            store.activate(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                activatedAtEpochMillis = revision * 1_000 + 100,
                statistics = SourceRevisionStatistics(1, 0, 0),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun entry(
        revision: Long,
        variantId: String,
        locator: String,
    ) = StagedCatalogEntry(
        providerChannelId = "provider-r$revision",
        providerKey = "tvg:channel-one",
        rawName = "Channel One",
        canonicalChannelId = CHANNEL_ID,
        canonicalDisplayName = "Channel One",
        streamVariantId = variantId,
        locator = locator,
        tvgId = "channel-one",
        tvgName = "Channel One",
        groupTitle = "General",
        channelNumber = "1",
    )

    private suspend fun snapshot(): UserStateSnapshot =
        database.useReaderConnection { connection ->
            val overlay = connection.usePrepared(
                """
                SELECT isFavorite, isHidden, customName, channelNumber
                FROM user_channel_overlays
                WHERE profileId = ? AND canonicalChannelId = ?
                LIMIT 1
                """.trimIndent(),
            ) { statement ->
                statement.bindText(1, PROFILE_ID)
                statement.bindText(2, CHANNEL_ID)
                check(statement.step()) { "Expected persisted user overlay." }
                OverlaySnapshot(
                    isFavorite = statement.getLong(0) != 0L,
                    isHidden = statement.getLong(1) != 0L,
                    customName = if (statement.isNull(2)) null else statement.getText(2),
                    channelNumber = if (statement.isNull(3)) null else statement.getLong(3).toInt(),
                )
            }
            val recentAt = connection.usePrepared(
                """
                SELECT lastSuccessfulPlaybackAtEpochMillis
                FROM recent_channels
                WHERE profileId = ? AND canonicalChannelId = ?
                LIMIT 1
                """.trimIndent(),
            ) { statement ->
                statement.bindText(1, PROFILE_ID)
                statement.bindText(2, CHANNEL_ID)
                check(statement.step()) { "Expected persisted Recent row." }
                statement.getLong(0)
            }
            UserStateSnapshot(overlay = overlay, recentAt = recentAt)
        }

    private data class OverlaySnapshot(
        val isFavorite: Boolean,
        val isHidden: Boolean,
        val customName: String?,
        val channelNumber: Int?,
    )

    private data class UserStateSnapshot(
        val overlay: OverlaySnapshot,
        val recentAt: Long,
    )

    private companion object {
        const val PROFILE_ID = "profile-x05-v11"
        const val SOURCE_ID = "source-x05-v11"
        const val CHANNEL_ID = "canonical-x05-v11"
        const val CUSTOM_NAME = "My News"
        const val CUSTOM_NUMBER = 77
        const val RECENT_AT = 42_000L
    }
}

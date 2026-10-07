package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogShadowProjectionParityTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomSourceRevisionStore(database.sourceRevisionDao())
        database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Primary",
                isPrimary = true,
            ),
        )
        store.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Provider",
                credentialRef = CREDENTIAL_REF,
            ),
        )
        store.beginRevision(SOURCE_ID, 1, 1_000)
        store.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            entries = listOf(
                entry(
                    providerId = "provider-news-a",
                    providerKey = "tvg:news",
                    canonicalId = NEWS_ID,
                    displayName = "News",
                    variantId = "variant-news-a",
                    locator = "https://stream.invalid/news-a",
                    group = "Information",
                    number = "10",
                    catchupSource = "?utc={utc}",
                ),
                entry(
                    providerId = "provider-news-b",
                    providerKey = "provider:news-backup",
                    canonicalId = NEWS_ID,
                    displayName = "News Backup",
                    variantId = "variant-news-b",
                    locator = "https://stream.invalid/news-b",
                    group = "Information",
                    number = "10",
                ),
                entry(
                    providerId = "provider-sports",
                    providerKey = "tvg:sports",
                    canonicalId = SPORTS_ID,
                    displayName = "Sports",
                    variantId = "variant-sports",
                    locator = "https://stream.invalid/sports",
                    group = "Sports",
                    number = "20",
                ),
            ),
        )
        assertThat(
            store.activate(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                activatedAtEpochMillis = 2_000,
                statistics = SourceRevisionStatistics(3, 0, 0),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = NEWS_ID,
                isFavorite = true,
                customName = "My News",
                channelNumber = 7,
            ),
        )
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = SPORTS_ID,
                isHidden = true,
            ),
        )
        database.recentChannelsDao().recordSuccessfulPlayback(
            profileId = PROFILE_ID,
            channelId = NEWS_ID,
            successfulAtEpochMillis = 42_000,
            retentionLimit = 50,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun channelSummaryProjectionMatchesAuthoritativeRepresentation() = runTest {
        val authoritative = database.playbackCatalogDao()
            .observeActiveChannels(
                profileId = PROFILE_ID,
                searchPattern = null,
                favoritesOnly = false,
                limit = 100,
            )
            .first()
        val shadow = database.catalogShadowDao().activeChannelSummaries(
            profileId = PROFILE_ID,
            searchPattern = null,
            favoritesOnly = false,
            limit = 100,
        )

        assertThat(shadow).isEqualTo(authoritative)
        assertThat(shadow.single().displayName).isEqualTo("My News")
        assertThat(shadow.single().channelNumber).isEqualTo("7")
        assertThat(shadow.single().isFavorite).isTrue()
        assertThat(shadow.single().variantCount).isEqualTo(2)

        val authoritativeFiltered = database.playbackCatalogDao()
            .observeActiveChannels(
                profileId = PROFILE_ID,
                searchPattern = "%My%",
                favoritesOnly = true,
                limit = 100,
            )
            .first()
        val shadowFiltered = database.catalogShadowDao().activeChannelSummaries(
            profileId = PROFILE_ID,
            searchPattern = "%My%",
            favoritesOnly = true,
            limit = 100,
        )
        assertThat(shadowFiltered).isEqualTo(authoritativeFiltered)
    }

    @Test
    fun playbackIdentityAndAccessProjectionsMatchAuthoritativeRepresentation() = runTest {
        val authoritativeVariants = database.playbackCatalogDao().getActiveVariants(NEWS_ID)
        val shadowVariants = database.catalogShadowDao().activeVariants(NEWS_ID)
        assertThat(shadowVariants).isEqualTo(authoritativeVariants)

        val authoritativeIdentities = database.playbackCatalogDao().getActiveVariantIdentities(
            profileId = PROFILE_ID,
            channelId = NEWS_ID,
            preferredVariantId = "variant-news-b",
            limit = 3,
        )
        val shadowIdentities = database.catalogShadowDao().activeVariantIdentities(
            profileId = PROFILE_ID,
            channelId = NEWS_ID,
            preferredVariantId = "variant-news-b",
            limit = 3,
        )
        assertThat(shadowIdentities).isEqualTo(authoritativeIdentities)

        authoritativeIdentities.forEach { identity ->
            val authoritativeAccess = database.playbackCatalogDao().findActiveVariantAccess(
                profileId = PROFILE_ID,
                channelId = identity.channelId,
                variantId = identity.variantId,
            )
            val shadowAccess = database.catalogShadowDao().activeVariantAccess(
                profileId = PROFILE_ID,
                channelId = identity.channelId,
                variantId = identity.variantId,
            )
            assertThat(shadowAccess).isEqualTo(authoritativeAccess)
        }

        assertThat(
            database.catalogShadowDao().activeVariantAccess(
                profileId = PROFILE_ID,
                channelId = NEWS_ID,
                variantId = "stale-variant",
            ),
        ).isNull()
    }

    @Test
    fun guideChannelWindowMatchesAuthoritativeRepresentation() = runTest {
        val authoritative = database.guideWindowDao().channelWindow(
            profileId = PROFILE_ID,
            afterHasChannelNumber = false,
            afterChannelNumber = null,
            afterDisplayName = null,
            afterCanonicalChannelId = null,
            limit = 100,
        )
        val shadow = database.catalogShadowDao().guideChannelWindow(
            profileId = PROFILE_ID,
            afterHasChannelNumber = false,
            afterChannelNumber = null,
            afterDisplayName = null,
            afterCanonicalChannelId = null,
            limit = 100,
        )

        assertThat(shadow).isEqualTo(authoritative)
        assertThat(shadow.single().channelId).isEqualTo(NEWS_ID)
        assertThat(shadow.single().cursorChannelNumber).isEqualTo(7)
        assertThat(shadow.single().variantCount).isEqualTo(2)
    }

    @Test
    fun recentProjectionMatchesAuthoritativeRepresentation() = runTest {
        val authoritative = database.recentChannelsDao()
            .observeRecent(PROFILE_ID, limit = 50)
            .first()
        val shadow = database.catalogShadowDao().recentRows(PROFILE_ID, limit = 50)

        assertThat(shadow).isEqualTo(authoritative)
        assertThat(shadow.single().channelId).isEqualTo(NEWS_ID)
        assertThat(shadow.single().lastSuccessfulPlaybackAtEpochMillis).isEqualTo(42_000)
    }

    private fun entry(
        providerId: String,
        providerKey: String,
        canonicalId: String,
        displayName: String,
        variantId: String,
        locator: String,
        group: String,
        number: String,
        catchupSource: String? = null,
    ) = StagedCatalogEntry(
        providerChannelId = providerId,
        providerKey = providerKey,
        rawName = displayName,
        canonicalChannelId = canonicalId,
        canonicalDisplayName = displayName,
        streamVariantId = variantId,
        locator = locator,
        tvgId = providerKey.removePrefix("tvg:"),
        tvgName = displayName,
        logoUrl = "https://images.invalid/$canonicalId.png",
        groupTitle = group,
        channelNumber = number,
        catchupMode = catchupSource?.let { "default" },
        catchupSource = catchupSource,
        catchupDays = catchupSource?.let { 7 },
        catchupCorrection = catchupSource?.let { "+00:00" },
        userAgent = "MuxTV-Test",
        referrer = "https://referrer.invalid/",
    )

    private companion object {
        const val PROFILE_ID = "profile-shadow-parity"
        const val SOURCE_ID = "source-shadow-parity"
        const val CREDENTIAL_REF = "00000000-0000-4000-8000-000000000388"
        const val NEWS_ID = "canonical-news"
        const val SPORTS_ID = "canonical-sports"
    }
}

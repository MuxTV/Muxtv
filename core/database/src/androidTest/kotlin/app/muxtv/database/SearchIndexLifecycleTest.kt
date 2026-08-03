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
class SearchIndexLifecycleTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore
    private lateinit var epgStore: EpgRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        epgStore = RoomEpgRevisionStore(database.epgRevisionDao())
        sourceStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Source",
                credentialRef = null,
            ),
        )
        epgStore.upsertSource(
            EpgSourceDefinition(
                id = EPG_SOURCE_ID,
                name = "EPG",
                providerSourceId = null,
                accessRef = null,
                defaultZoneId = "UTC",
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun catalogStagingIndexesProviderMetadataButNotUnpublishedCanonicalRename() = runTest {
        stageCatalogRevision(1, displayName = "Принятое имя")

        assertThat(providerTexts()).containsExactly("Принятое имя", "Новости", "101")
        assertThat(canonicalTexts()).isEmpty()

        sourceStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 20,
            statistics = sourceStatistics(),
        )
        assertThat(canonicalTexts()).containsExactly("Принятое имя")

        stageCatalogRevision(2, displayName = "Непринятое имя")

        assertThat(canonicalTexts()).containsExactly("Принятое имя")
        assertThat(providerTexts()).containsAtLeast(
            "Принятое имя",
            "Непринятое имя",
            "Новости",
            "101",
            "102",
        )

        sourceStore.discard(SOURCE_ID, 2)

        assertThat(canonicalTexts()).containsExactly("Принятое имя")
        assertThat(providerTexts()).doesNotContain("Непринятое имя")
        assertThat(providerTexts()).doesNotContain("102")
    }

    @Test
    fun activationRefreshesCanonicalDocumentAndPrunesOldProviderDocuments() = runTest {
        stageCatalogRevision(1, displayName = "Первое имя")
        sourceStore.activate(SOURCE_ID, 1, 20, sourceStatistics())
        stageCatalogRevision(2, displayName = "Второе имя")
        sourceStore.activate(SOURCE_ID, 2, 30, sourceStatistics())
        stageCatalogRevision(3, displayName = "Третье имя")

        sourceStore.activate(SOURCE_ID, 3, 40, sourceStatistics())

        assertThat(canonicalTexts()).containsExactly("Третье имя")
        assertThat(providerTexts()).doesNotContain("Первое имя")
        assertThat(providerTexts()).doesNotContain("101")
        assertThat(providerTexts()).containsAtLeast("Второе имя", "Третье имя", "102", "103")
    }

    @Test
    fun overlayWriteReplacesProfileScopedNameAndNumberDocuments() = runTest {
        stageCatalogRevision(1, displayName = "Канал")
        sourceStore.activate(SOURCE_ID, 1, 20, sourceStatistics())

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                customName = "Мой канал",
                channelNumber = 77,
            ),
        )
        assertThat(overlayTexts()).containsExactly("Мой канал", "77")

        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                canonicalChannelId = CHANNEL_ID,
                customName = "Новое имя",
                channelNumber = null,
            ),
        )

        assertThat(overlayTexts()).containsExactly("Новое имя")
    }

    @Test
    fun epgStagingIndexesTitlesAndDiscardRemovesThem() = runTest {
        val revision = epgStore.beginRevision(EPG_SOURCE_ID, startedAtEpochMillis = 10)
        val programme = programme(revision, sequence = 1, title = "Вести")

        epgStore.stageBatch(channels = emptyList(), programmes = listOf(programme))

        assertThat(epgTexts(EPG_SOURCE_ID, revision)).containsExactly("Вести")

        epgStore.discardRevision(EPG_SOURCE_ID, revision)

        assertThat(epgTexts(EPG_SOURCE_ID, revision)).isEmpty()
    }

    @Test
    fun epgActivationRetainsCurrentAndPreviousSearchDocumentsOnly() = runTest {
        val first = stageEpgRevision("Первая")
        epgStore.activateRevision(EPG_SOURCE_ID, first, 20, epgStatistics())
        val second = stageEpgRevision("Вторая")
        epgStore.activateRevision(EPG_SOURCE_ID, second, 30, epgStatistics())
        val third = stageEpgRevision("Третья")

        epgStore.activateRevision(EPG_SOURCE_ID, third, 40, epgStatistics())

        assertThat(epgTexts(EPG_SOURCE_ID, first)).isEmpty()
        assertThat(epgTexts(EPG_SOURCE_ID, second)).containsExactly("Вторая")
        assertThat(epgTexts(EPG_SOURCE_ID, third)).containsExactly("Третья")
    }

    private suspend fun stageCatalogRevision(revision: Long, displayName: String) {
        sourceStore.beginRevision(SOURCE_ID, revision, startedAtEpochMillis = 10)
        sourceStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revision,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-$revision",
                    providerKey = "provider-key-$revision",
                    rawName = displayName,
                    canonicalChannelId = CHANNEL_ID,
                    canonicalDisplayName = displayName,
                    streamVariantId = "variant-$revision",
                    locator = "https://example.invalid/$revision?token=must-not-be-indexed",
                    groupTitle = "Новости",
                    channelNumber = (100 + revision).toString(),
                    tvgName = displayName,
                ),
            ),
        )
    }

    private suspend fun stageEpgRevision(title: String): Long {
        val revision = epgStore.beginRevision(EPG_SOURCE_ID, startedAtEpochMillis = 10)
        epgStore.stageBatch(
            channels = emptyList(),
            programmes = listOf(programme(revision, sequence = 1, title = title)),
        )
        return revision
    }

    private fun programme(revision: Long, sequence: Long, title: String) = EpgProgrammeEntity(
        sourceId = EPG_SOURCE_ID,
        revisionNumber = revision,
        sequenceNumber = sequence,
        externalChannelId = "epg-channel",
        startEpochMillis = 1_000,
        stopEpochMillis = 2_000,
        primaryTitle = title,
        primaryLanguage = "ru",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )

    private suspend fun canonicalTexts(): List<String> =
        database.searchIndexDao().textsForCanonicalKind(CHANNEL_ID, SearchDocumentKind.CANONICAL_NAME)

    private suspend fun providerTexts(): List<String> =
        database.searchIndexDao().textsForProviderKinds(
            listOf(
                SearchDocumentKind.PROVIDER_RAW_NAME,
                SearchDocumentKind.PROVIDER_GROUP,
                SearchDocumentKind.PROVIDER_NUMBER,
            ),
        )

    private suspend fun overlayTexts(): List<String> =
        database.searchIndexDao().textsForProfileCanonicalKinds(
            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
            canonicalChannelId = CHANNEL_ID,
            kinds = listOf(
                SearchDocumentKind.OVERLAY_CUSTOM_NAME,
                SearchDocumentKind.OVERLAY_NUMBER,
            ),
        )

    private suspend fun epgTexts(sourceId: String, revision: Long): List<String> =
        database.searchIndexDao().textsForEpgRevision(sourceId, revision)

    private fun sourceStatistics() = SourceRevisionStatistics(
        parsedEntries = 1,
        skippedEntries = 0,
        warningCount = 0,
    )

    private fun epgStatistics() = EpgRevisionStatistics(
        acceptedChannels = 0,
        acceptedProgrammes = 1,
        skippedProgrammes = 0,
        warningCount = 0,
        unresolvedTimeCount = 0,
    )

    private companion object {
        const val SOURCE_ID = "search-source"
        const val EPG_SOURCE_ID = "search-epg"
        const val CHANNEL_ID = "search-channel"
    }
}

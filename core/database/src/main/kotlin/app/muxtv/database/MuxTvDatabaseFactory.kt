package app.muxtv.database

import android.content.Context
import androidx.room3.Room
import app.muxtv.catalog.CatalogRepository
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.catalog.RejectAllPlaybackAccessPolicyResolver
import app.muxtv.catalog.UnhandledPlaybackArchiveResolver
import app.muxtv.catalog.UnhandledPlaybackReferenceResolver

class MuxTvDatabaseComponents internal constructor(
    val initializer: DatabaseInitializer,
    val sourceRevisionStore: SourceRevisionStore,
    val sourceRefreshStore: SourceRefreshStore,
    val pendingSourcePreparationStore: PendingSourcePreparationStore,
    val catalogRepository: CatalogRepository,
    val playbackCatalog: PlaybackCatalog,
    val channelBrowseRepository: ChannelBrowseRepository,
    val playbackCandidateResolver: PlaybackCandidateResolver,
    val channelPreferencesRepository: ChannelPreferencesRepository,
    val recentChannelsRepository: RecentChannelsRepository,
    val channelSearchRepository: ChannelSearchRepository,
    val epgRevisionStore: EpgRevisionStore,
    val epgRefreshStore: EpgRefreshStore,
    val epgMatchingStore: EpgMatchingStore,
    val epgGuideRepository: EpgGuideRepository,
    val guideWindowRepository: GuideWindowRepository,
)

object MuxTvDatabaseFactory {
    fun create(
        context: Context,
        playbackAccessPolicyResolver: PlaybackAccessPolicyResolver =
            RejectAllPlaybackAccessPolicyResolver,
        playbackReferenceResolver: PlaybackReferenceResolver =
            UnhandledPlaybackReferenceResolver,
        playbackArchiveResolver: PlaybackArchiveResolver =
            UnhandledPlaybackArchiveResolver,
    ): MuxTvDatabaseComponents {
        val database = Room.databaseBuilder(
            context = context.applicationContext,
            klass = MuxTvDatabase::class.java,
            name = "muxtv.db",
        )
            .addMigrations(*CURRENT_DATABASE_MIGRATIONS)
            .build()
        val epgGuideRepository = RoomEpgGuideRepository(database.epgGuideDao())
        val guideWindowRepository = RoomGuideWindowRepository(
            dao = database.guideWindowDao(),
            invalidationDao = database.guideWindowInvalidationDao(),
        )
        val roomPlaybackCatalog = RoomPlaybackCatalog(
            dao = database.playbackCatalogDao(),
            accessPolicyResolver = playbackAccessPolicyResolver,
            playbackReferenceResolver = playbackReferenceResolver,
            playbackArchiveResolver = playbackArchiveResolver,
        )
        return MuxTvDatabaseComponents(
            initializer = DatabaseInitializer(database),
            sourceRevisionStore = RoomSourceRevisionStore(database.sourceRevisionDao()),
            sourceRefreshStore = RoomSourceRefreshStore(database.sourceRefreshDao()),
            pendingSourcePreparationStore = RoomPendingSourcePreparationStore(
                database.pendingSourcePreparationDao(),
            ),
            catalogRepository = RoomCatalogRepository(database.catalogDao()),
            playbackCatalog = roomPlaybackCatalog,
            channelBrowseRepository = RoomChannelBrowseRepository(
                dao = database.channelBrowseDao(),
                guideRepository = epgGuideRepository,
            ),
            playbackCandidateResolver = roomPlaybackCatalog,
            channelPreferencesRepository = RoomChannelPreferencesRepository(
                database.channelPreferencesDao(),
            ),
            recentChannelsRepository = RoomRecentChannelsRepository(database.recentChannelsDao()),
            channelSearchRepository = RoomChannelSearchRepository(
                dataSource = database.channelSearchDao(),
                guideRepository = epgGuideRepository,
            ),
            epgRevisionStore = RoomEpgRevisionStore(database.epgRevisionDao()),
            epgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao()),
            epgMatchingStore = RoomEpgMatchingStore(database.epgMatchingDao()),
            epgGuideRepository = epgGuideRepository,
            guideWindowRepository = guideWindowRepository,
        )
    }

    fun createInitializer(context: Context): DatabaseInitializer =
        create(context).initializer
}

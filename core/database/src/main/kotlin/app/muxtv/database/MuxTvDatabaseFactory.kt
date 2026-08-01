package app.muxtv.database

import android.content.Context
import androidx.room3.Room
import app.muxtv.catalog.CatalogRepository
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RejectAllPlaybackAccessPolicyResolver

class MuxTvDatabaseComponents internal constructor(
    val initializer: DatabaseInitializer,
    val sourceRevisionStore: SourceRevisionStore,
    val sourceRefreshStore: SourceRefreshStore,
    val pendingSourcePreparationStore: PendingSourcePreparationStore,
    val catalogRepository: CatalogRepository,
    val playbackCatalog: PlaybackCatalog,
    val epgRevisionStore: EpgRevisionStore,
    val epgRefreshStore: EpgRefreshStore,
)

object MuxTvDatabaseFactory {
    fun create(
        context: Context,
        playbackAccessPolicyResolver: PlaybackAccessPolicyResolver =
            RejectAllPlaybackAccessPolicyResolver,
    ): MuxTvDatabaseComponents {
        val database = Room.databaseBuilder(
            context = context.applicationContext,
            klass = MuxTvDatabase::class.java,
            name = "muxtv.db",
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            )
            .build()
        return MuxTvDatabaseComponents(
            initializer = DatabaseInitializer(database),
            sourceRevisionStore = RoomSourceRevisionStore(database.sourceRevisionDao()),
            sourceRefreshStore = RoomSourceRefreshStore(database.sourceRefreshDao()),
            pendingSourcePreparationStore = RoomPendingSourcePreparationStore(
                database.pendingSourcePreparationDao(),
            ),
            catalogRepository = RoomCatalogRepository(database.catalogDao()),
            playbackCatalog = RoomPlaybackCatalog(
                dao = database.playbackCatalogDao(),
                accessPolicyResolver = playbackAccessPolicyResolver,
            ),
            epgRevisionStore = RoomEpgRevisionStore(database.epgRevisionDao()),
            epgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao()),
        )
    }

    fun createInitializer(context: Context): DatabaseInitializer =
        create(context).initializer
}

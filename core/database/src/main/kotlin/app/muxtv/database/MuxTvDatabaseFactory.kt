package app.muxtv.database

import android.content.Context
import androidx.room3.Room
import app.muxtv.catalog.CatalogRepository
import app.muxtv.catalog.PlaybackCatalog

class MuxTvDatabaseComponents internal constructor(
    val initializer: DatabaseInitializer,
    val sourceRevisionStore: SourceRevisionStore,
    val sourceRefreshStore: SourceRefreshStore,
    val pendingSourcePreparationStore: PendingSourcePreparationStore,
    val catalogRepository: CatalogRepository,
    val playbackCatalog: PlaybackCatalog,
)

object MuxTvDatabaseFactory {
    fun create(context: Context): MuxTvDatabaseComponents {
        val database = Room.databaseBuilder(
            context = context.applicationContext,
            klass = MuxTvDatabase::class.java,
            name = "muxtv.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        return MuxTvDatabaseComponents(
            initializer = DatabaseInitializer(database),
            sourceRevisionStore = RoomSourceRevisionStore(database.sourceRevisionDao()),
            sourceRefreshStore = RoomSourceRefreshStore(database.sourceRefreshDao()),
            pendingSourcePreparationStore = RoomPendingSourcePreparationStore(
                database.pendingSourcePreparationDao(),
            ),
            catalogRepository = RoomCatalogRepository(database.catalogDao()),
            playbackCatalog = RoomPlaybackCatalog(database.playbackCatalogDao()),
        )
    }

    fun createInitializer(context: Context): DatabaseInitializer =
        create(context).initializer
}

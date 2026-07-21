package app.muxtv.database

import android.content.Context
import androidx.room3.Room
import app.muxtv.catalog.CatalogRepository

class MuxTvDatabaseComponents internal constructor(
    val initializer: DatabaseInitializer,
    val sourceRevisionStore: SourceRevisionStore,
    val catalogRepository: CatalogRepository,
)

object MuxTvDatabaseFactory {
    fun create(context: Context): MuxTvDatabaseComponents {
        val database = Room.databaseBuilder(
            context = context.applicationContext,
            klass = MuxTvDatabase::class.java,
            name = "muxtv.db",
        )
            .addMigrations(MIGRATION_1_2)
            .build()
        return MuxTvDatabaseComponents(
            initializer = DatabaseInitializer(database),
            sourceRevisionStore = RoomSourceRevisionStore(database.sourceRevisionDao()),
            catalogRepository = RoomCatalogRepository(database.catalogDao()),
        )
    }

    fun createInitializer(context: Context): DatabaseInitializer =
        create(context).initializer
}

package app.muxtv.database

import android.content.Context
import androidx.room3.Room

class MuxTvDatabaseComponents internal constructor(
    val initializer: DatabaseInitializer,
    val sourceRevisionStore: SourceRevisionStore,
)

object MuxTvDatabaseFactory {
    fun create(context: Context): MuxTvDatabaseComponents {
        val database = Room.databaseBuilder(
            context = context.applicationContext,
            klass = MuxTvDatabase::class.java,
            name = "muxtv.db",
        ).build()
        return MuxTvDatabaseComponents(
            initializer = DatabaseInitializer(database),
            sourceRevisionStore = RoomSourceRevisionStore(database.sourceRevisionDao()),
        )
    }

    fun createInitializer(context: Context): DatabaseInitializer =
        create(context).initializer
}

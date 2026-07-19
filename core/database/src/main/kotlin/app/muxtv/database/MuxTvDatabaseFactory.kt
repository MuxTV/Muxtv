package app.muxtv.database

import android.content.Context
import androidx.room3.Room

object MuxTvDatabaseFactory {
    fun createInitializer(context: Context): DatabaseInitializer {
        val database = Room.databaseBuilder(
            context = context.applicationContext,
            klass = MuxTvDatabase::class.java,
            name = "muxtv.db",
        ).build()
        return DatabaseInitializer(database)
    }
}

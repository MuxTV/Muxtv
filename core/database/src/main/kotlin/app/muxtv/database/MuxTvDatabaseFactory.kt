package app.muxtv.database

import android.content.Context
import androidx.room3.Room

object MuxTvDatabaseFactory {
    fun create(context: Context): MuxTvDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        klass = MuxTvDatabase::class.java,
        name = "muxtv.db",
    ).build()
}

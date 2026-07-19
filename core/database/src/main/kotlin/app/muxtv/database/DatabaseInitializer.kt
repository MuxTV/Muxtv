package app.muxtv.database

class DatabaseInitializer internal constructor(
    private val database: MuxTvDatabase,
) {
    suspend fun initialize() {
        database.initializationDao().ensureInitialized()
    }
}

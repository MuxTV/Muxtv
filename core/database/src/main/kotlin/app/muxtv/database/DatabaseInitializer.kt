package app.muxtv.database

class DatabaseInitializer(
    private val database: MuxTvDatabase,
) {
    suspend fun initialize() {
        database.initializationDao().ensureInitialized()
    }
}

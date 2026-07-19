package app.muxtv.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        InstallationEntity::class,
        ProfileEntity::class,
        SourceEntity::class,
        ProviderChannelEntity::class,
        CanonicalChannelEntity::class,
        StreamVariantEntity::class,
        UserChannelOverlayEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MuxTvDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun catalogDao(): CatalogDao
    internal abstract fun initializationDao(): InitializationDao
}

package app.muxtv.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        InstallationEntity::class,
        ProfileEntity::class,
        SourceEntity::class,
        SourceRevisionEntity::class,
        SourceRefreshPolicyEntity::class,
        SourceRefreshStateEntity::class,
        SourceRefreshAttemptEntity::class,
        ProviderChannelEntity::class,
        CanonicalChannelEntity::class,
        StreamVariantEntity::class,
        UserChannelOverlayEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class MuxTvDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun catalogDao(): CatalogDao
    abstract fun initializationDao(): InitializationDao
    abstract fun sourceRevisionDao(): SourceRevisionDao
    abstract fun sourceRefreshDao(): SourceRefreshDao
}

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
        PendingSourcePreparationEntity::class,
        ProviderChannelEntity::class,
        CanonicalChannelEntity::class,
        StreamVariantEntity::class,
        UserChannelOverlayEntity::class,
        RecentChannelEntity::class,
        EpgSourceEntity::class,
        EpgRevisionEntity::class,
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        EpgRefreshPolicyEntity::class,
        EpgRefreshStateEntity::class,
        EpgRefreshAttemptEntity::class,
        EpgRefreshHttpValidatorEntity::class,
        EpgChannelMatchEntity::class,
        SearchDocumentEntity::class,
        SearchDocumentFtsEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
internal abstract class MuxTvDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun catalogDao(): CatalogDao
    abstract fun initializationDao(): InitializationDao
    abstract fun sourceRevisionDao(): SourceRevisionDao
    abstract fun sourceRefreshDao(): SourceRefreshDao
    abstract fun pendingSourcePreparationDao(): PendingSourcePreparationDao
    abstract fun playbackCatalogDao(): PlaybackCatalogDao
    abstract fun channelPreferencesDao(): ChannelPreferencesDao
    abstract fun recentChannelsDao(): RecentChannelsDao
    abstract fun epgRevisionDao(): EpgRevisionDao
    abstract fun epgRefreshDao(): EpgRefreshDao
    abstract fun epgMatchingDao(): EpgMatchingDao
    abstract fun epgGuideDao(): EpgGuideDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun channelSearchDao(): ChannelSearchDao
}

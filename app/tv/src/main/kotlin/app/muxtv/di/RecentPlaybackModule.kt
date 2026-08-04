package app.muxtv.di

import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.database.MuxTvDatabaseComponents
import app.muxtv.player.media3.PlaybackFirstFrameObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object RecentPlaybackModule {
    @Provides
    fun provideRecentChannelsRepository(
        components: MuxTvDatabaseComponents,
    ): RecentChannelsRepository = components.recentChannelsRepository

    @Provides
    @Singleton
    fun provideRecentPlaybackObserver(
        repository: RecentChannelsRepository,
    ): RecentPlaybackObserver = RecentPlaybackObserver(
        repository = repository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowEpochMillis = System::currentTimeMillis,
    )

    @Provides
    @IntoSet
    fun provideRecentPlaybackFirstFrameObserver(
        observer: RecentPlaybackObserver,
    ): PlaybackFirstFrameObserver = observer
}

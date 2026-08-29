package app.muxtv.di

import app.muxtv.ApplicationIoScope
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
        @ApplicationIoScope scope: CoroutineScope,
    ): RecentPlaybackObserver = RecentPlaybackObserver(
        repository = repository,
        scope = scope,
        nowEpochMillis = System::currentTimeMillis,
    )

    @Provides
    @IntoSet
    fun provideRecentPlaybackFirstFrameObserver(
        observer: RecentPlaybackObserver,
    ): PlaybackFirstFrameObserver = observer
}

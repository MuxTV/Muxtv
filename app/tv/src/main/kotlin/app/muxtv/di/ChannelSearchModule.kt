package app.muxtv.di

import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.database.MuxTvDatabaseComponents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ChannelSearchModule {
    @Provides
    fun provideChannelSearchRepository(
        components: MuxTvDatabaseComponents,
    ): ChannelSearchRepository = components.channelSearchRepository
}

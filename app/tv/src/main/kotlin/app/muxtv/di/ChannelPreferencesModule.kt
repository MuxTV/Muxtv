package app.muxtv.di

import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.database.MuxTvDatabaseComponents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ChannelPreferencesModule {
    @Provides
    fun provideChannelPreferencesRepository(
        components: MuxTvDatabaseComponents,
    ): ChannelPreferencesRepository = components.channelPreferencesRepository
}

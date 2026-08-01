package app.muxtv.di

import app.muxtv.database.EpgRefreshStore
import app.muxtv.database.MuxTvDatabaseComponents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object EpgRefreshModule {
    @Provides
    fun provideEpgRefreshStore(
        components: MuxTvDatabaseComponents,
    ): EpgRefreshStore = components.epgRefreshStore
}

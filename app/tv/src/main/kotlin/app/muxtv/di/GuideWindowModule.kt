package app.muxtv.di

import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.database.MuxTvDatabaseComponents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object GuideWindowModule {
    @Provides
    fun provideGuideWindowRepository(
        components: MuxTvDatabaseComponents,
    ): GuideWindowRepository = components.guideWindowRepository
}

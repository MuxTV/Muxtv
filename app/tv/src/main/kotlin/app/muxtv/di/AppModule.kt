package app.muxtv.di

import android.content.Context
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.MuxTvDatabaseComponents
import app.muxtv.database.MuxTvDatabaseFactory
import app.muxtv.database.SourceRevisionStore
import app.muxtv.player.PlaybackEngine
import app.muxtv.player.media3.Media3PlaybackEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabaseComponents(
        @ApplicationContext context: Context,
    ): MuxTvDatabaseComponents = MuxTvDatabaseFactory.create(context)

    @Provides
    fun provideDatabaseInitializer(
        components: MuxTvDatabaseComponents,
    ): DatabaseInitializer = components.initializer

    @Provides
    fun provideSourceRevisionStore(
        components: MuxTvDatabaseComponents,
    ): SourceRevisionStore = components.sourceRevisionStore

    @Provides
    @Singleton
    fun providePlaybackEngine(@ApplicationContext context: Context): PlaybackEngine =
        Media3PlaybackEngineFactory.create(context)
}

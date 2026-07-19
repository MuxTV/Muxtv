package app.muxtv.di

import android.content.Context
import app.muxtv.database.DatabaseInitializer
import app.muxtv.database.MuxTvDatabaseFactory
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
    fun provideDatabaseInitializer(@ApplicationContext context: Context): DatabaseInitializer =
        MuxTvDatabaseFactory.createInitializer(context)

    @Provides
    @Singleton
    fun providePlaybackEngine(@ApplicationContext context: Context): PlaybackEngine =
        Media3PlaybackEngineFactory.create(context)
}

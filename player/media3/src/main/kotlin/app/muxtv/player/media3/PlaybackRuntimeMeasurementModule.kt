package app.muxtv.player.media3

import app.muxtv.player.PlaybackRuntimeMeasurementReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PlaybackRuntimeMeasurementModule {
    @Provides
    @Singleton
    fun providePlaybackRuntimeMeasurementState(): PlaybackRuntimeMeasurementState =
        PlaybackRuntimeMeasurementState()

    @Provides
    @Singleton
    fun providePlaybackRuntimeMeasurementReader(
        state: PlaybackRuntimeMeasurementState,
    ): PlaybackRuntimeMeasurementReader = state
}

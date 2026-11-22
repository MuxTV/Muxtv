package app.muxtv.di

import app.muxtv.external.LocalNetworkSourcePreflight
import app.muxtv.player.media3.PlaybackLocalNetworkAccessGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaybackLocalNetworkModule {
    @Provides
    @Singleton
    fun providePlaybackLocalNetworkAccessGate(
        localNetworkPreflight: LocalNetworkSourcePreflight,
    ): PlaybackLocalNetworkAccessGate = PlaybackLocalNetworkAccessGate(
        localNetworkPreflight::accessRequired,
    )
}
